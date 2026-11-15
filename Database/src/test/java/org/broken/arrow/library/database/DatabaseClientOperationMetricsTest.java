package org.broken.arrow.library.database;

import java.util.concurrent.CompletionException;

import org.broken.arrow.library.database.api.DatabaseClient;
import org.broken.arrow.library.database.config.DatabaseConfiguration;
import org.broken.arrow.library.database.config.DatabaseKind;
import org.broken.arrow.library.database.schema.CollectionDefinition;
import org.broken.arrow.library.database.session.DatabaseWork;
import org.broken.arrow.library.database.session.ReadSession;
import org.broken.arrow.library.database.session.WriteSession;
import org.broken.arrow.library.database.spi.DatabaseProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies that real DatabaseClient provider entry points are counted exactly once. */
final class DatabaseClientOperationMetricsTest {

    /** Sync and async callbacks share a single metric per execution, including failures. */
    @Test
    void measuresBlockingAndAsyncProviderCallbacksWithoutDoubleCounting() {
        DatabaseConfiguration configuration = DatabaseConfiguration.forKind(DatabaseKind.SQLITE,
                "jdbc:sqlite::memory:").workers(2, 32).operationLatencyWindowSeconds(60).build();
        try (DatabaseClient client = DatabaseClient.fromProvider(new StubProvider(), configuration)) {
            assertEquals("read", client.read(session -> "read"));
            assertEquals("async", client.readAsync(session -> "async").join());
            assertEquals(42, client.writeOne(session -> 42).intValue());
            assertEquals("tx", client.transactionAsync(session -> "tx").join());
            assertThrows(CompletionException.class, () -> client.writeOneAsync(session -> {
                throw new IllegalStateException("simulated failure");
            }).join());

            var measured = client.operationMetrics();
            assertEquals(60, measured.windowSeconds());
            assertEquals(2, measured.reads().successfulTotal());
            assertEquals(2, measured.reads().samples());
            assertEquals(1, measured.writes().successfulTotal());
            assertEquals(1, measured.writes().failedTotal());
            assertEquals(2, measured.writes().samples());
            assertEquals(1, measured.transactions().completedTotal());
            assertTrue(measured.reads().p95UpperMillis() >= 1);
            assertEquals(0, client.snapshot().queued());
        }
    }

    /** In-memory provider executes callbacks without requiring any external SQL driver. */
    private static final class StubProvider implements DatabaseProvider {

        /** @return in-memory test configuration type */
        @Override
        public DatabaseKind kind() { return DatabaseKind.SQLITE; }

        /** @return true, as this fake accepts transaction work */
        @Override
        public boolean supportsTransactions() { return true; }

        /** @return the callback result */
        @Override
        public <T> T read(DatabaseWork<ReadSession, T> work) { return invoke(work); }

        /** @return the callback result */
        @Override
        public <T> T writeOne(DatabaseWork<WriteSession, T> work) { return invoke(work); }

        /** @return the callback result */
        @Override
        public <T> T transaction(DatabaseWork<WriteSession, T> work) { return invoke(work); }

        /** No SQL schema is necessary for the stub. */
        @Override
        public void createIfAbsent(CollectionDefinition schema) { }

        /** Releases no external resources. */
        @Override
        public void close() { }

        /** Executes a callback while preserving runtime exception semantics. */
        private static <S, T> T invoke(DatabaseWork<S, T> work) {
            try {
                return work.execute(null);
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }
}
