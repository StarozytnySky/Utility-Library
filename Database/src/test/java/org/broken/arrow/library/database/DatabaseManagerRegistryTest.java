package org.broken.arrow.library.database;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.broken.arrow.library.database.api.DatabaseClient;
import org.broken.arrow.library.database.api.DatabaseManager;
import org.broken.arrow.library.database.config.DatabaseConfiguration;
import org.broken.arrow.library.database.config.DatabaseKind;
import org.broken.arrow.library.database.schema.CollectionDefinition;
import org.broken.arrow.library.database.schema.FieldType;

import org.broken.arrow.library.database.schema.StoredCollection;
import org.broken.arrow.library.database.session.DatabaseWork;
import org.broken.arrow.library.database.session.ReadSession;
import org.broken.arrow.library.database.session.WriteSession;
import org.broken.arrow.library.database.spi.DatabaseProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Fast lifecycle tests independent of JDBC or MongoDB drivers. */
class DatabaseManagerRegistryTest {
    @StoredCollection("guild_roles")
    private record GuildRole(UUID id, String roleKey) { }

    private record Unannotated(UUID id) { }

    /** Ensures that the schema list is frozen and each schema is created only once. */
    @Test
    void registrationAndOneTimeInitialization() {
        FakeProvider provider = new FakeProvider();
        DatabaseConfiguration configuration = DatabaseConfiguration.forKind(
                DatabaseKind.SQLITE, "jdbc:sqlite:ignored.sqlite").build();
        try (DatabaseManager manager = DatabaseManager.withClient(DatabaseClient.fromProvider(provider, configuration))) {
            var roles = manager.<GuildRole, UUID>register(GuildRole.class);
            assertFalse(manager.isReady());
            assertThrows(IllegalStateException.class, () -> manager.repository(GuildRole.class));
            assertThrows(IllegalArgumentException.class, () -> manager.register(Unannotated.class));
            assertThrows(IllegalArgumentException.class,
                    () -> manager.register(GuildRole.class, "duplicate", "id"));
            var future = manager.initializeSchemasAsync();
            assertSame(future, manager.initializeSchemasAsync());
            future.join();
            assertTrue(manager.isReady());
            assertEquals(List.of("guild_roles"), provider.created);
            assertSame(roles, manager.<GuildRole, UUID>repository(GuildRole.class));
            assertThrows(IllegalStateException.class,
                    () -> manager.registerSchema(roles.schema()));
        }
        assertTrue(provider.closed);
    }

    /** Rejects accidental runtime schema changes after initialization failure. */
    @Test
    void failedInitializationCannotBecomeReady() {
        FakeProvider provider = new FakeProvider();
        provider.fail = true;
        DatabaseConfiguration configuration = DatabaseConfiguration.forKind(
                DatabaseKind.SQLITE, "jdbc:sqlite:ignored.sqlite").build();
        try (DatabaseManager manager = DatabaseManager.withClient(DatabaseClient.fromProvider(provider, configuration))) {
            manager.register(GuildRole.class);
            assertThrows(RuntimeException.class, () -> manager.initializeSchemasAsync().join());
            assertFalse(manager.isReady());
            assertThrows(IllegalStateException.class, () -> manager.registerSchema(
                    CollectionDefinition.named("another", "id", org.broken.arrow.library.database.schema.FieldType.UUID)
                            .build()));
        }
    }

    private static final class FakeProvider implements DatabaseProvider {
        private final List<String> created = new ArrayList<>();
        private boolean fail;
        private boolean closed;

        @Override public DatabaseKind kind() { return DatabaseKind.SQLITE; }
        @Override public boolean supportsTransactions() { return true; }
        @Override public <T> T read(DatabaseWork<ReadSession, T> work) { throw new UnsupportedOperationException(); }
        @Override public <T> T writeOne(DatabaseWork<WriteSession, T> work) { throw new UnsupportedOperationException(); }
        @Override public <T> T transaction(DatabaseWork<WriteSession, T> work) { throw new UnsupportedOperationException(); }
        @Override public void createIfAbsent(CollectionDefinition schema) {
            if (fail) throw new IllegalStateException("Mock schema failure");
            created.add(schema.name());
        }
        @Override public void close() { closed = true; }
    }
}
