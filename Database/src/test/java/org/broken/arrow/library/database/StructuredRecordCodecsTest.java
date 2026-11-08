package org.broken.arrow.library.database;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.broken.arrow.library.database.codec.RecordCodec;
import org.broken.arrow.library.database.codec.RecordCodecs;
import org.broken.arrow.library.database.codec.ValueCodec;
import org.broken.arrow.library.database.codec.ValueCodecs;
import org.broken.arrow.library.database.repository.RecordQuery;
import org.broken.arrow.library.database.schema.FieldType;
import org.broken.arrow.library.database.schema.StoredField;
import org.broken.arrow.library.serialize.utility.serialize.ConfigurationSerializable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises portable typed storage without a running database. */
class StructuredRecordCodecsTest {

    private enum Permission { INVITE, KICK, EDIT_ROLE }

    private enum Source { DEFAULT, CUSTOM }

    private record Visual(String color, List<String> gradients, @StoredField(nullable = true) String symbol) { }

    private record Role(
            @StoredField(name = "role_id") UUID roleId,
            UUID guildId,
            String roleKey,
            Set<Permission> permissions,
            Set<String> unknownPermissions,
            Map<String, Long> limits,
            List<UUID> members,
            Visual visual,
            @StoredField(nullable = true) Instant expiresAt,
            Source source) { }

    /** Collections, enums, nested records, UUID and nullable fields are losslessly reconstructed. */
    @Test
    void mapsPortableRoleRecordWithoutManualSerializer() {
        RecordCodec<Role, UUID> codec = RecordCodecs.of(Role.class, "guild_roles", "roleId");
        Role original = new Role(UUID.randomUUID(), UUID.randomUUID(), "moderator",
                Set.of(Permission.INVITE, Permission.KICK), Set.of("CUSTOM_PERMISSION"),
                Map.of("members", 64L), List.of(UUID.randomUUID()),
                new Visual("#F0F0F0", List.of("#FFFFFF", "#00FF00"), null),
                null, Source.CUSTOM);

        assertEquals("role_id", codec.schema().idField());
        assertEquals(FieldType.JSON, codec.schema().field("permissions").type());
        assertEquals(FieldType.JSON, codec.schema().field("visual").type());
        assertEquals(original, codec.decode(codec.encode(original)));
        assertEquals(original.roleId(), codec.id(original));
        assertThrows(UnsupportedOperationException.class,
                () -> codec.encode(original).put("permissions", null));
    }

    private record Price(BigDecimal value) { }

    private record Item(UUID id, List<Price> prices, Map<String, Set<UUID>> references,
                        Map<UUID, Set<Permission>> roleBindings) { }

    /** Nested BigDecimal numbers and UUIDs are stringified, not passed as arbitrary Java objects. */
    @Test
    void nestedScalarsStayPortable() {
        RecordCodec<Item, UUID> codec = RecordCodecs.of(Item.class, "items", "id");
        Item item = new Item(UUID.randomUUID(), List.of(new Price(new BigDecimal("1234567890.123400"))),
                Map.of("relations", Set.of(UUID.randomUUID())),
                Map.of(UUID.randomUUID(), Set.of(Permission.EDIT_ROLE)));
        Map<String, Object> encoded = codec.encode(item);
        assertEquals(item, codec.decode(encoded));
    }

    private record Money(String currency, long amount) { }

    private record Payment(UUID id, Money money) { }

    /** Explicit type codecs can override built-in structured record mapping. */
    @Test
    void registeredCustomTypeCodecIsUsed() {
        ValueCodec<Money> custom = new ValueCodec<>() {
            /** Serializes currency and long amount to one stable string. */
            @Override public Object encode(Money money) {
                return money.currency() + ":" + money.amount();
            }

            /** Parses explicitly formatted persisted currency values. */
            @Override public Money decode(Object stored) {
                String[] parts = stored.toString().split(":", 2);
                return new Money(parts[0], Long.parseLong(parts[1]));
            }
        };
        ValueCodecs codecs = ValueCodecs.defaults().with(Money.class, custom);
        RecordCodec<Payment, UUID> codec = RecordCodecs.of(Payment.class, "payments", "id", codecs);
        Payment payment = new Payment(UUID.randomUUID(), new Money("EUR", 50));
        assertEquals("EUR:50", codec.encode(payment).get("money"));
        assertEquals(payment, codec.decode(codec.encode(payment)));
    }

    private record Account(UUID id, @StoredField(codec = MoneyCodec.class) Money balance) { }

    /** Example stateless annotation codec usable without registering a global type mapping. */
    public static final class MoneyCodec implements ValueCodec<Money> {
        /** Encodes the amount and ISO currency. */
        @Override public Object encode(Money value) {
            return Map.of("currency", value.currency(), "amount", value.amount());
        }

        /** Decodes the stable storage map without untyped reflection. */
        @Override public Money decode(Object stored) {
            Map<?, ?> map = (Map<?, ?>) stored;
            return new Money((String) map.get("currency"), ((Number) map.get("amount")).longValue());
        }
    }

    /** Field annotations can select a dedicated codec. */
    @Test
    void annotationCodecOverridesDefaultMapping() {
        RecordCodec<Account, UUID> codec = RecordCodecs.of(Account.class, "accounts", "id");
        Account original = new Account(UUID.randomUUID(), new Money("PLN", 999));
        assertEquals(original, codec.decode(codec.encode(original)));
    }

    private record LegacyConfig(String label) implements ConfigurationSerializable {
        /** Returns the existing Serialize Utility map representation. */
        @Override public Map<String, Object> serialize() { return Map.of("Label", label); }

        /**
         * Restores a configuration value from its portable map.
         *
         * @param source source value to convert
         * @return computed result of the requested operation
         */
        static LegacyConfig from(Map<String, Object> source) {
            return new LegacyConfig((String) source.get("Label"));
        }
    }

    private record Configured(UUID id, LegacyConfig configuration) { }

    /** Existing ConfigurationSerializable models can be used with an explicit restore factory. */
    @Test
    void configurationSerializableAdapterReusesExistingSerializeUtilityContract() {
        ValueCodecs codecs = ValueCodecs.defaults().with(LegacyConfig.class,
                ValueCodecs.configurationSerializable(LegacyConfig::from));
        RecordCodec<Configured, UUID> codec = RecordCodecs.of(Configured.class, "configurations", "id", codecs);
        Configured input = new Configured(UUID.randomUUID(), new LegacyConfig("hello"));
        assertEquals(input, codec.decode(codec.encode(input)));
    }

    private record Invalid(UUID id, List<?> unknown) { }

    /** Avoids implicit reflection, wildcard erasure and backend-dependent coercion. */
    @Test
    void rejectsUnsupportedModelsAndUnsafeQueriesAtRegistration() {
        assertThrows(IllegalArgumentException.class,
                () -> RecordCodecs.of(Invalid.class, "invalid", "id"));
        RecordCodec<Role, UUID> codec = RecordCodecs.of(Role.class, "guild_roles", "roleId");
        assertThrows(IllegalArgumentException.class,
                () -> RecordQuery.all().where("permissions", RecordQuery.Operator.EQ,
                        List.of("INVITE")).validate(codec.schema()));
        assertThrows(IllegalArgumentException.class,
                () -> RecordQuery.all().orderBy("visual", true).validate(codec.schema()));
    }
}
