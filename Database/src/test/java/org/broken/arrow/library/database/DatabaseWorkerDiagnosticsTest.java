package org.broken.arrow.library.database;

import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.broken.arrow.library.database.internal.BoundedDatabaseWorker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Worker saturation must remain observable and must never execute SQL on the caller. */
final class DatabaseWorkerDiagnosticsTest {

    /** A full bounded queue counts rejected submissions separately from executed callbacks. */
    @Test
    void reportsActualCapacityAndRejectedSubmissions() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var worker = new BoundedDatabaseWorker(1, 1, Duration.ofSeconds(5), "db-metrics-test")) {
            var first = worker.submit(() -> {
                started.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("release timeout");
                return 1;
            });
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                var queued = worker.submit(() -> 2);
                var rejected = worker.submit(() -> 3);
                assertThrows(CompletionException.class, rejected::join);
                var metrics = worker.snapshot();
                assertEquals(1, metrics.queueCapacity());
                assertEquals(1, metrics.maximumThreads());
                assertEquals(1, metrics.rejectedTasks());
                assertEquals(1, metrics.queued());
                release.countDown();
                assertEquals(1, first.join().intValue());
                assertEquals(2, queued.join().intValue());
            } finally {
                release.countDown();
            }
        }
    }
}
