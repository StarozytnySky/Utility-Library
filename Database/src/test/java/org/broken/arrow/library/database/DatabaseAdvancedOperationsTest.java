package org.broken.arrow.library.database;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.broken.arrow.library.database.api.DatabaseManager;
import org.broken.arrow.library.database.config.DatabaseConfiguration;
import org.broken.arrow.library.database.config.DatabaseKind;
import org.broken.arrow.library.database.jdbc.SqlDialect;
import org.broken.arrow.library.database.repository.RecordQuery;
import org.broken.arrow.library.database.schema.CollectionDefinition;
import org.broken.arrow.library.database.schema.FieldType;
import org.broken.arrow.library.database.schema.StoredCollection;
import org.broken.arrow.library.database.schema.StoredIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Provider-level verification for indexes, conditional mutations and exact aggregates. */
final class DatabaseAdvancedOperationsTest {
    @TempDir Path temporary;

    @StoredCollection("scored_accounts")
    @StoredIndex(name = "idx_scored_scope_key", columns = {"scope", "code"}, unique = true)
    @StoredIndex(name = "idx_scored_scope", columns = {"scope"})
    private record Score(UUID id, UUID scope, String code, long points) { }

    /** Exercises complete transaction/DDL behavior on real SQLite and H2 drivers. */
    @Test
    void createsIndexesAndPerformsConditionalMutations() {
        for (DatabaseKind kind : List.of(DatabaseKind.SQLITE, DatabaseKind.H2)) {
            String address = kind == DatabaseKind.SQLITE
                    ? "jdbc:sqlite:" + temporary.resolve("advanced-" + UUID.randomUUID() + ".sqlite")
                    : "jdbc:h2:mem:advanced_" + UUID.randomUUID().toString().replace('-', '_') + ";DB_CLOSE_DELAY=-1";
            DatabaseConfiguration config = DatabaseConfiguration.forKind(kind, address)
                    .poolSize(1).workers(2, 64).build();
            UUID scope = UUID.randomUUID();
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            try (DatabaseManager manager = DatabaseManager.open(config)) {
                var records = manager.<Score, UUID>register(Score.class);
                manager.initializeSchemasAsync().join();
                // Schema initialization must be repeatable with existing indexes.
                manager.client().createIfAbsentAsync(records.schema()).join();
                records.insertBatchAsync(List.of(new Score(alice, scope, "a", 8L),
                        new Score(bob, scope, "b", 12L)), 2).join();

                var query = RecordQuery.all().where("scope", RecordQuery.Operator.EQ, scope);
                assertEquals(20L, records.sumLongAsync(query, "points").join().longValue());
                assertEquals(1, records.groupSumLongAsync(query, "scope", "points", 5).join().size());
                assertEquals(20L, records.groupSumLongAsync(query, "scope", "points", 5)
                        .join().getFirst().sum());

                RecordQuery aliceQuery = query.where("code", RecordQuery.Operator.EQ, "a");
                assertEquals(1L, records.updateMatchingAsync(aliceQuery,
                        java.util.Map.of("points", 15L)).join().longValue());
                assertEquals(27L, records.sumLongAsync(query, "points").join().longValue());
                assertThrows(java.util.concurrent.CompletionException.class,
                        () -> records.updateExactlyAsync(aliceQuery,
                                java.util.Map.of("points", 900L), 2L).join());
                assertEquals(15L, records.findByIdAsync(alice).join().orElseThrow().points());

                // An existing primary key is a no-op, not an error.
                assertFalse(records.insertIfAbsentAsync(new Score(alice, scope, "a", 15L)).join());
                // A different primary key violating a secondary unique index must fail.
                assertThrows(java.util.concurrent.CompletionException.class,
                        () -> records.insertIfAbsentAsync(new Score(UUID.randomUUID(), scope, "a", 0)).join());
                // Ordinary INSERT must enforce the same secondary unique constraint.
                assertThrows(java.util.concurrent.CompletionException.class,
                        () -> records.insertAsync(new Score(UUID.randomUUID(), scope, "a", 0)).join());
                assertEquals(2L, records.countAsync(query).join().longValue());
            }
        }
    }

    /** Invalid indexes fail during registration rather than on the database thread. */
    @Test
    void rejectsUnsafeIndexDefinitions() {
        assertThrows(IllegalArgumentException.class,
                () -> CollectionDefinition.named("data", "id", FieldType.UUID)
                        .field("json", FieldType.JSON, false)
                        .index("invalid_json_index", false, "json").build());
        assertThrows(IllegalArgumentException.class,
                () -> CollectionDefinition.named("data", "id", FieldType.UUID)
                        .field("optional", FieldType.STRING, true)
                        .index("invalid_nullable_unique", true, "optional").build());
        assertThrows(IllegalArgumentException.class,
                () -> CollectionDefinition.named("data", "id", FieldType.UUID)
                        .field("value", FieldType.LONG, false)
                        .index("unknown_field", false, "missing").build());
        var schema = CollectionDefinition.named("data", "id", FieldType.UUID)
                .field("value", FieldType.LONG, false)
                .index("idx_values", false, "value").build();
        assertTrue(new SqlDialect(DatabaseKind.POSTGRESQL).createIndex(schema,
                schema.indexes().getFirst()).contains("CREATE INDEX"));
    }
}
