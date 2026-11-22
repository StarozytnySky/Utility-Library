package org.broken.arrow.library.database;

import org.broken.arrow.library.database.api.DatabaseManager;
import org.broken.arrow.library.database.config.DatabaseConfiguration;
import org.broken.arrow.library.database.config.DatabaseKind;
import org.broken.arrow.library.database.jdbc.SqlDialect;
import org.broken.arrow.library.database.schema.CompositeKey;
import org.broken.arrow.library.database.schema.StoredCollection;
import org.broken.arrow.library.database.schema.StoredField;
import org.broken.arrow.library.database.schema.StoredIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Integration tests for compound identities and integer-safe conditional mutations. */
final class CompositeKeyRecordTest {
    @TempDir Path directory;

    @StoredCollection(value = "compound_balances", idFields = {"player", "kind"})
    @StoredIndex(name = "idx_compound_kind", columns = {"Kind"})
    private record Balance(@StoredField(name = "Player") String player,
                           @StoredField(name = "Kind") String kind,
                           @StoredField(name = "Amount") int amount,
                           @StoredField(name = "Updated") long updated) { }

    /** Exercises real JDBC providers without relying on read-modify-write snapshots. */
    @Test
    void rejectsOverdraftAndOverflowForCompositeIntegerKeys() {
        for (DatabaseKind kind : List.of(DatabaseKind.H2, DatabaseKind.SQLITE)) {
            String url = kind == DatabaseKind.SQLITE
                    ? "jdbc:sqlite:" + directory.resolve("compound_" + UUID.randomUUID() + ".db")
                    : "jdbc:h2:mem:compound_" + UUID.randomUUID().toString().replace('-', '_')
                      + ";DB_CLOSE_DELAY=-1";
            DatabaseConfiguration configuration = DatabaseConfiguration.forKind(kind, url)
                    .poolSize(1).workers(2, 64).build();
            try (DatabaseManager manager = DatabaseManager.open(configuration)) {
                var repository = manager.<Balance, CompositeKey>register(Balance.class);
                manager.initializeSchemasAsync().join();
                CompositeKey id = CompositeKey.of("player-1", "crate");
                repository.insertAsync(new Balance("player-1", "crate", 9, 1L)).join();
                assertTrue(manager.client().<Boolean>transaction(session ->
                        session.incrementBounded(repository.schema(), id, "Amount", -4, 0, Integer.MAX_VALUE)));
                assertFalse(manager.client().<Boolean>transaction(session ->
                        session.incrementBounded(repository.schema(), id, "Amount", -6, 0, Integer.MAX_VALUE)));
                assertFalse(manager.client().<Boolean>transaction(session ->
                        session.incrementBounded(repository.schema(), id, "Amount", Integer.MAX_VALUE, 0, Integer.MAX_VALUE)));
                assertEquals(5, repository.findByIdAsync(id).join().orElseThrow().amount());
                assertTrue(manager.client().<Boolean>transaction(session ->
                        session.compareAndSet(repository.schema(), id, "Amount", 5, 7)));
                assertFalse(manager.client().<Boolean>transaction(session ->
                        session.compareAndSet(repository.schema(), id, "Amount", 5, 8)));
                assertEquals(7, repository.findByIdAsync(id).join().orElseThrow().amount());
                assertFalse(repository.insertIfAbsentAsync(new Balance("player-1", "crate", 99, 2L)).join());
                assertTrue(repository.insertIfAbsentAsync(new Balance("player-1", "other", 3, 3L)).join());
                assertEquals(3, repository.findByIdAsync(CompositeKey.of("player-1", "other"))
                        .join().orElseThrow().amount());
            }
        }
    }

    /** Ensures every dialect uses the full key for UPSERT conflict resolution. */
    @Test
    void rendersAllCompositeKeyColumns() {
        var schema = org.broken.arrow.library.database.schema.CollectionDefinition.namedComposite("compound_balances",
                        java.util.Map.of("Player", org.broken.arrow.library.database.schema.FieldType.STRING,
                                "Kind", org.broken.arrow.library.database.schema.FieldType.STRING))
                .field("Amount", org.broken.arrow.library.database.schema.FieldType.INTEGER, false).build();
        for (DatabaseKind kind : List.of(DatabaseKind.SQLITE, DatabaseKind.POSTGRESQL, DatabaseKind.H2)) {
            SqlDialect dialect = new SqlDialect(kind);
            String ddl = dialect.createTable(schema);
            assertTrue(ddl.contains("PRIMARY KEY"));
            assertTrue(ddl.contains("Player"));
            assertTrue(ddl.contains("Kind"));
        }
    }
}
