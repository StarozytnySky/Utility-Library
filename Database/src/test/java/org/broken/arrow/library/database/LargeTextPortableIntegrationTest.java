package org.broken.arrow.library.database;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.broken.arrow.library.database.api.DatabaseManager;
import org.broken.arrow.library.database.codec.RecordCodecs;
import org.broken.arrow.library.database.config.DatabaseConfiguration;
import org.broken.arrow.library.database.config.DatabaseKind;
import org.broken.arrow.library.database.jdbc.SqlDialect;
import org.broken.arrow.library.database.schema.StoredField;
import org.broken.arrow.library.database.schema.StoredIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Regression coverage for large UTF-8 payloads used by external plugins and item storage. */
final class LargeTextPortableIntegrationTest {
    @TempDir Path temporary;

    /** A mapped, unindexed large text column must round-trip above H2's bounded VARCHAR limit. */
    @Test
    void largeTextRoundTripsOnSqliteAndH2() {
        String content = "Stored NBT: " + "⚒漢字 🔒".repeat(15000);
        for (DatabaseKind kind : List.of(DatabaseKind.SQLITE, DatabaseKind.H2)) {
            String url = kind == DatabaseKind.SQLITE
                    ? "jdbc:sqlite:" + temporary.resolve(UUID.randomUUID() + ".db")
                    : "jdbc:h2:mem:large_text_" + UUID.randomUUID().toString().replace('-', '_') + ";DB_CLOSE_DELAY=-1";
            try (DatabaseManager manager = DatabaseManager.open(DatabaseConfiguration.forKind(kind, url)
                    .poolSize(1).workers(2, 128).build())) {
                var repository = manager.<LargeTextRow, UUID>register(LargeTextRow.class, "large_text_rows", "id");
                manager.initializeSchemasAsync().join();
                UUID id = UUID.randomUUID();
                repository.insertAsync(new LargeTextRow(id, content)).join();
                assertEquals(content, repository.findByIdAsync(id).join().orElseThrow().payload());
            }
        }
    }

    /** The schema must reject indexed large text and non-String large-text declarations. */
    @Test
    void largeTextRequiresUnindexedStrings() {
        assertThrows(IllegalArgumentException.class, () -> RecordCodecs.of(IndexedTextRow.class,
                "invalid_longtext_index", "id"));
        assertThrows(IllegalArgumentException.class, () -> RecordCodecs.of(InvalidTextTypeRow.class,
                "invalid_longtext_type", "id"));
        assertThrows(IllegalArgumentException.class, () -> RecordCodecs.of(InvalidTextKeyRow.class,
                "invalid_longtext_key", "id"));
        var codec = RecordCodecs.of(LargeTextRow.class, "large_text_rows", "id");
        assertTrue(new SqlDialect(DatabaseKind.H2).createTable(codec.schema()).contains("CLOB"));
        assertTrue(new SqlDialect(DatabaseKind.MYSQL).createTable(codec.schema()).contains("LONGTEXT"));
    }

    /** One full-length structured item payload. */
    record LargeTextRow(@StoredField(name = "id") UUID id,
                        @StoredField(name = "payload", longText = true) String payload) { }

    /** Invalid indexed CLOB declaration. */
    @StoredIndex(name = "large_text_invalid_index", columns = {"payload"})
    record IndexedTextRow(@StoredField(name = "id") UUID id,
                          @StoredField(name = "payload", longText = true) String payload) { }

    /** Invalid attempt to apply a string-only modifier to an integer. */
    record InvalidTextTypeRow(@StoredField(name = "id") UUID id,
                              @StoredField(name = "payload", longText = true) long payload) { }

    /** Invalid attempt to index a large-text primary key. */
    record InvalidTextKeyRow(@StoredField(name = "id", longText = true) String id) { }
}
