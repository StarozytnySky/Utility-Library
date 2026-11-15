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

    /**
     * Simulates 20, 50 and 100 concurrent requests against the actual bounded DB2 worker.
     * It verifies rejection, success accounting and shutdown without external SQL drivers.
     */
    @Test
    void boundedWorkerHandlesSyntheticTwentyFiftyHundredRequestsWithoutUnboundedQueue() {
        for (int producers : new int[] {20, 50, 100}) {
            var configuration = DatabaseConfiguration.forKind(DatabaseKind.SQLITE,
                    "jdbc:sqlite::memory:").workers(4, 8).build();
            var release = new java.util.concurrent.CountDownLatch(1);
            try (DatabaseClient client = DatabaseClient.fromProvider(new StubProvider(), configuration)) {
                var requests = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Integer>>();
                for (int index = 0; index < producers; index++) {
                    requests.add(client.readAsync(session -> {
                        if (!release.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Synthetic provider execution timed out");
                        }
                        return 1;
                    }));
                }
                var pending = client.snapshot();
                assertTrue(pending.running() <= 4);
                assertTrue(pending.queued() <= 8);
                assertEquals(8, pending.queueCapacity());
                assertEquals(4, pending.maximumThreads());
                assertEquals(producers - 12, pending.rejectedTasks());

                release.countDown();
                java.util.concurrent.CompletableFuture.allOf(requests.toArray(
                        java.util.concurrent.CompletableFuture[]::new))
                        .handle((unused, failure) -> null).join();
                assertEquals(12, requests.stream().filter(request -> !request.isCompletedExceptionally()).count());
                assertEquals(12, client.operationMetrics().reads().successfulTotal());
                assertEquals(0, client.snapshot().queued());
            } finally {
                release.countDown();
            }
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

        /**
         * Executes a callback while preserving runtime exception semantics.
         *
         * @param <S> session type
         * @param <T> callback result type
         * @param work callback to execute with an in-memory session
         * @return result returned by the callback
         */
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
