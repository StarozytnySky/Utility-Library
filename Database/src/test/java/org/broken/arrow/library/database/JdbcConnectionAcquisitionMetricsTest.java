package org.broken.arrow.library.database;

import org.broken.arrow.library.database.api.DatabaseClient;
import org.broken.arrow.library.database.config.DatabaseConfiguration;
import org.broken.arrow.library.database.config.DatabaseKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Measures real SQLite/Hikari connection borrows without relying on guessed SQL times. */
final class JdbcConnectionAcquisitionMetricsTest {

    /** A connection borrow must be counted once even when the SQL callback is trivial. */
    @Test
    void observesActualHikariBorrowAndRollingPercentiles() {
        var configuration = DatabaseConfiguration.forKind(DatabaseKind.SQLITE, "jdbc:sqlite::memory:")
                .operationLatencyWindowSeconds(60).build();
        try (DatabaseClient client = DatabaseClient.open(configuration)) {
            var initial = client.poolMetrics().orElseThrow();
            assertEquals(60, initial.acquisitionWindowSeconds());
            assertNotNull(initial.acquisitions());
            assertTrue(initial.acquisitions().successfulTotal() >= 1);

            assertEquals("first", client.read(session -> "first"));
            assertEquals("second", client.transaction(session -> "second"));
            var measured = client.poolMetrics().orElseThrow();
            assertTrue(measured.acquisitions().successfulTotal() >= 3);
            assertTrue(measured.acquisitions().samples() >= 3);
            assertEquals(0, measured.acquisitions().failedTotal());
            assertTrue(measured.acquisitions().p50UpperMillis() >= 1);
            assertTrue(measured.acquisitions().p95UpperMillis() >= measured.acquisitions().p50UpperMillis());
            assertTrue(measured.acquisitions().p99UpperMillis() >= measured.acquisitions().p95UpperMillis());
        }
    }
}
