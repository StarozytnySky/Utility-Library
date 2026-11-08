package org.broken.arrow.library.database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.broken.arrow.library.database.api.DatabaseManager;
import org.broken.arrow.library.database.config.DatabaseConfiguration;
import org.broken.arrow.library.database.config.DatabaseConfigurationLoader;
import org.broken.arrow.library.database.config.DatabaseKind;
import org.broken.arrow.library.database.repository.RecordRepository;
import org.broken.arrow.library.database.schema.StoredCollection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Integration coverage for standalone and plugin-oriented database bootstrap. */
class DatabaseManagerBootstrapTest {
    @TempDir Path temporary;

    private enum Permission { INVITE, MANAGE }
    @StoredCollection("roles")
    private record Role(UUID id, UUID guildId, Set<Permission> permissions, Instant createdAt) { }
    @StoredCollection("banks")
    private record Bank(UUID id, long balance) { }

    /** Checks that one initialization creates every repository schema on real SQL engines. */
    @Test
    void registersAndInitializesMultipleRepositories() {
        for (DatabaseKind kind : List.of(DatabaseKind.SQLITE, DatabaseKind.H2)) {
            String address = kind == DatabaseKind.SQLITE
                    ? "jdbc:sqlite:" + temporary.resolve("data-" + UUID.randomUUID() + ".sqlite")
                    : "jdbc:h2:mem:test_" + UUID.randomUUID().toString().replace('-', '_') + ";DB_CLOSE_DELAY=-1";
            DatabaseConfiguration config = DatabaseConfiguration.forKind(kind, address)
                    .poolSize(1).workers(2, 128).build();
            try (DatabaseManager manager = DatabaseManager.open(config)) {
                RecordRepository<Role, UUID> roles = manager.<Role, UUID>register(Role.class);
                RecordRepository<Bank, UUID> banks = manager.<Bank, UUID>register(Bank.class);
                var initialized = manager.initializeSchemasAsync();
                assertSame(initialized, manager.initializeSchemasAsync());
                initialized.join();
                assertTrue(manager.isReady());
                assertSame(roles, manager.<Role, UUID>repository(Role.class));
                assertSame(banks, manager.<Bank, UUID>repository(Bank.class));
                assertThrows(IllegalStateException.class,
                        () -> manager.registerSchema(roles.schema()));
                Role role = new Role(UUID.randomUUID(), UUID.randomUUID(), Set.of(Permission.INVITE), Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
                roles.insertAsync(role).join();
                assertEquals(role, roles.findByIdAsync(role.id()).join().orElseThrow());
                Bank bank = new Bank(UUID.randomUUID(), 20);
                banks.insertAsync(bank).join();
                assertEquals(bank, banks.findByIdAsync(bank.id()).join().orElseThrow());
            }
        }
    }

    /** Rejects duplicate registrations and access before initialization succeeds. */
    @Test
    void rejectsDuplicateModelsAndPrematureAccess() {
        DatabaseConfiguration configuration = DatabaseConfiguration.forKind(DatabaseKind.H2,
                "jdbc:h2:mem:dup_" + UUID.randomUUID().toString().replace('-', '_')).build();
        try (DatabaseManager manager = DatabaseManager.open(configuration)) {
            manager.<Role, UUID>register(Role.class);
            assertThrows(IllegalArgumentException.class,
                    () -> manager.register(Role.class, "new_roles", "id"));
            assertThrows(IllegalArgumentException.class,
                    () -> manager.register(Bank.class, "roles", "id"));
            assertThrows(IllegalStateException.class,
                    () -> manager.repository(Role.class));
            manager.initializeSchemasAsync().join();
        }
    }

    /** Verifies that YAML settings cannot escape the plugin's data directory. */
    @Test
    void rejectsInvalidAndEscapingConfiguration() throws Exception {
        Path file = temporary.resolve("database.yml");
        Files.writeString(file, "database:\n  type: SQLITE\n  sqlite-file: ../outside.db\n");
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseConfigurationLoader.load(file, temporary));

        Files.writeString(file, "database:\n  type: UNKNOWN\n  sqlite-file: local.db\n");
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseConfigurationLoader.load(file, temporary));

        Files.writeString(file, "database:\n  type: SQLITE\n  sqlite-file: data/records.db\n");
        var loaded = DatabaseConfigurationLoader.load(file, temporary);
        assertEquals(DatabaseKind.SQLITE, loaded.kind());
        assertEquals(1, loaded.poolSize());
        assertTrue(loaded.address().endsWith("data/records.db"));
    }
}
