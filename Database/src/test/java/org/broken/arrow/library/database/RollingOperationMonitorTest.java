package org.broken.arrow.library.database;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.broken.arrow.library.database.internal.RollingOperationMonitor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Monotonic deterministic verification of the bounded provider-operation histograms. */
final class RollingOperationMonitorTest {

    /** Confirms callback categories, failures, quantiles and bounded time expiry. */
    @Test
    void separatesProviderOperationsAndExpiresOnlyRecentDurations() {
        AtomicLong nanos = new AtomicLong(TimeUnit.SECONDS.toNanos(100));
        var monitor = new RollingOperationMonitor(10, nanos::get);
        for (int i = 0; i < 98; i++) monitor.record(RollingOperationMonitor.READ, 500_000, true);
        monitor.record(RollingOperationMonitor.READ, TimeUnit.MILLISECONDS.toNanos(100), false);
        monitor.record(RollingOperationMonitor.READ, TimeUnit.SECONDS.toNanos(120), true);
        monitor.record(RollingOperationMonitor.WRITE, TimeUnit.MILLISECONDS.toNanos(3), true);
        monitor.record(RollingOperationMonitor.TRANSACTION, TimeUnit.MILLISECONDS.toNanos(12), false);
        var sample = monitor.snapshot();
        assertEquals(100, sample.reads().completedTotal());
        assertEquals(1, sample.reads().failedTotal());
        assertEquals(100, sample.reads().samples());
        assertEquals(1, sample.reads().p50UpperMillis());
        assertEquals(1, sample.reads().p95UpperMillis());
        assertEquals(128, sample.reads().p99UpperMillis());
        assertEquals(4, sample.writes().p95UpperMillis());
        assertEquals(16, sample.transactions().p50UpperMillis());
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(10));
        assertEquals(-1, monitor.snapshot().reads().p95UpperMillis());
        assertEquals(0, monitor.snapshot().reads().samples());
        assertEquals(100, monitor.snapshot().reads().completedTotal());
        monitor.record(RollingOperationMonitor.READ, TimeUnit.MILLISECONDS.toNanos(20), true);
        assertEquals(32, monitor.snapshot().reads().p99UpperMillis());
        assertEquals(101, monitor.snapshot().reads().completedTotal());
    }

    /** Different operation types cannot share a lifetime counter or histogram slot. */
    @Test
    void rejectsInvalidWindowAndUnknownOperationKind() {
        assertThrows(IllegalArgumentException.class, () -> new RollingOperationMonitor(9));
        var monitor = new RollingOperationMonitor(60);
        assertThrows(IllegalArgumentException.class, () -> monitor.record(42, 1, true));
        assertEquals(-1, monitor.snapshot().transactions().p50UpperMillis());
    }
}
