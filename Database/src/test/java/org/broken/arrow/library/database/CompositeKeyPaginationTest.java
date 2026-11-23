package org.broken.arrow.library.database;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.broken.arrow.library.database.api.DatabaseManager;
import org.broken.arrow.library.database.config.DatabaseConfiguration;
import org.broken.arrow.library.database.config.DatabaseKind;
import org.broken.arrow.library.database.repository.RecordQuery;
import org.broken.arrow.library.database.schema.CompositeKey;
import org.broken.arrow.library.database.schema.StoredCollection;
import org.broken.arrow.library.database.schema.StoredField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies lexicographic pagination on compound identifiers, without OFFSET. */
final class CompositeKeyPaginationTest {
    @TempDir
    Path temp;

    @StoredCollection(value = "compound_page_test", idFields = {"player", "kind"})
    private record Row(@StoredField(name = "Player") String player,
                       @StoredField(name = "Kind") String kind,
                       @StoredField(name = "Amount") int amount) { }

    @StoredCollection(value = "triple_key_page_test", idFields = {"owner", "category", "tier"})
    private record Triple(@StoredField(name = "Owner") String owner,
                          @StoredField(name = "Category") String category,
                          @StoredField(name = "Tier") int tier) { }

    /** Verifies that all three key components contribute to stable page boundaries. */
    @Test
    void tripleKeysNeverSkipEqualPrefixes() {
        String url = "jdbc:h2:mem:triple_" + UUID.randomUUID().toString().replace('-', '_')
                + ";DB_CLOSE_DELAY=-1";
        try (DatabaseManager manager = DatabaseManager.open(DatabaseConfiguration.forKind(DatabaseKind.H2, url)
                .poolSize(1).workers(2, 64).build())) {
            var repository = manager.<Triple, CompositeKey>register(Triple.class);
            manager.initializeSchemasAsync().join();
            for (int tier = 0; tier < 5; tier++) {
                repository.insertAsync(new Triple("alice", "diamond", tier)).join();
            }
            repository.insertAsync(new Triple("alice", "emerald", 0)).join();
            repository.insertAsync(new Triple("bob", "diamond", 0)).join();
            var result = new ArrayList<Triple>();
            CompositeKey cursor = null;
            do {
                RecordQuery query = RecordQuery.all().page(0, 2);
                if (cursor != null) query = query.afterPrimaryKey(cursor);
                var rows = repository.findAsync(query).join();
                if (rows.isEmpty()) break;
                result.addAll(rows);
                Triple last = rows.getLast();
                cursor = CompositeKey.of(last.owner(), last.category(), last.tier());
            } while (true);
            assertEquals(7, result.size());
            assertEquals(0, result.get(5).tier());
            assertEquals("emerald", result.get(5).category());
        }
    }

    /** Tests ascending/descending compound scans and filtered page boundaries. */
    @Test
    void iteratesAllRowsWithRepeatedFirstComponents() {
        for (DatabaseKind kind : List.of(DatabaseKind.H2, DatabaseKind.SQLITE)) {
            String url = kind == DatabaseKind.SQLITE
                    ? "jdbc:sqlite:" + temp.resolve("keyset_" + UUID.randomUUID() + ".db")
                    : "jdbc:h2:mem:keyset_" + UUID.randomUUID().toString().replace('-', '_')
                            + ";DB_CLOSE_DELAY=-1";
            try (DatabaseManager manager = DatabaseManager.open(DatabaseConfiguration.forKind(kind, url)
                    .poolSize(1).workers(2, 64).build())) {
                var repository = manager.<Row, CompositeKey>register(Row.class);
                manager.initializeSchemasAsync().join();
                List<Row> rows = List.of(new Row("alice", "a", 1), new Row("alice", "b", 2),
                        new Row("alice", "c", 3), new Row("bob", "a", 4),
                        new Row("bob", "d", 5), new Row("carol", "a", 6));
                for (Row row : rows) repository.insertAsync(row).join();

                List<Row> forward = scan(repository, false, null);
                assertEquals(rows, forward);
                List<Row> backward = scan(repository, true, null);
                assertEquals(rows.reversed(), backward);
                List<Row> filtered = scan(repository, false,
                        RecordQuery.all().where("Player", RecordQuery.Operator.GTE, "bob"));
                assertEquals(rows.subList(3, rows.size()), filtered);
                assertThrows(IllegalArgumentException.class, () ->
                        RecordQuery.all().orderBy("Amount", false)
                                .afterPrimaryKey(CompositeKey.of("alice", "a"))
                                .validate(repository.schema()));
            }
        }
    }

    private static List<Row> scan(org.broken.arrow.library.database.repository.RecordRepository<Row, CompositeKey> repository,
                                   boolean descending, RecordQuery filter) {
        RecordQuery base = filter == null ? RecordQuery.all() : filter;
        base = base.orderBy("Player", descending);
        CompositeKey cursor = null;
        List<Row> result = new ArrayList<>();
        while (true) {
            RecordQuery next = base.page(0, 2);
            if (cursor != null) next = next.afterPrimaryKey(cursor);
            List<Row> batch = repository.findAsync(next).join();
            if (batch.isEmpty()) break;
            result.addAll(batch);
            Row last = batch.getLast();
            cursor = CompositeKey.of(last.player(), last.kind());
            if (batch.size() < 2) break;
        }
        return result;
    }
}
