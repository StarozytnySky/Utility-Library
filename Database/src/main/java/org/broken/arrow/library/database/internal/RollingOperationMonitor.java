package org.broken.arrow.library.database.internal;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

import org.broken.arrow.library.database.diagnostics.DatabaseOperationMetrics;

/**
 * Fixed-memory, thread-safe operation timer with one histogram per second per operation kind.
 *
 * <p>Neither operations nor SQL parameters are retained. An independent lock per second bucket
 * avoids a global mutex; a snapshot only reads bounded counters and never calls the provider.</p>
 */
public final class RollingOperationMonitor {
    public static final int READ = 0;
    public static final int WRITE = 1;
    public static final int TRANSACTION = 2;
    private static final long[] UPPER_MILLIS = {
            1, 2, 4, 8, 16, 32, 64, 128, 256, 512,
            1_024, 2_048, 4_096, 8_192, 16_384, 32_768, 65_536, Long.MAX_VALUE
    };

    private final int windowSeconds;
    private final LongSupplier ticker;
    private final Bucket[][] buckets;
    private final LongAdder[] successes = new LongAdder[3];
    private final LongAdder[] failures = new LongAdder[3];

    /**
     * Allocates a bounded recent-time histogram using the monotonic JVM clock.
     *
     * @param windowSeconds number of recent seconds retained (10-900)
     */
    public RollingOperationMonitor(int windowSeconds) {
        this(windowSeconds, System::nanoTime);
    }

    /**
     * Allows deterministic verification without changing production timekeeping.
     *
     * @param windowSeconds retained horizon
     * @param ticker monotonic nanosecond clock
     */
    public RollingOperationMonitor(int windowSeconds, LongSupplier ticker) {
        if (windowSeconds < 10 || windowSeconds > 900) {
            throw new IllegalArgumentException("operation latency window must be between 10 and 900 seconds");
        }
        this.windowSeconds = windowSeconds;
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.buckets = new Bucket[3][windowSeconds];
        for (int kind = 0; kind < buckets.length; kind++) {
            successes[kind] = new LongAdder();
            failures[kind] = new LongAdder();
            for (int second = 0; second < windowSeconds; second++) {
                buckets[kind][second] = new Bucket();
            }
        }
    }

    /**
     * Records one completed blocking provider callback, without exposing its contents.
     *
     * @param kind READ, WRITE or TRANSACTION
     * @param elapsedNanos execution duration excluding the scheduler queue wait
     * @param successful whether the callback returned normally
     */
    public void record(int kind, long elapsedNanos, boolean successful) {
        if (kind < READ || kind > TRANSACTION) throw new IllegalArgumentException("Unknown operation kind");
        long second = Math.floorDiv(ticker.getAsLong(), TimeUnit.SECONDS.toNanos(1));
        Bucket bucket = buckets[kind][Math.floorMod(second, windowSeconds)];
        long millis = TimeUnit.NANOSECONDS.toMillis(Math.max(0, elapsedNanos));
        int histogram = Arrays.binarySearch(UPPER_MILLIS, millis);
        if (histogram < 0) histogram = -histogram - 1;
        synchronized (bucket) {
            if (bucket.second != second) {
                Arrays.fill(bucket.counts, 0);
                bucket.second = second;
            }
            bucket.counts[histogram]++;
        }
        if (successful) successes[kind].increment();
        else failures[kind].increment();
    }

    /**
     * Reads a recent snapshot without storage I/O or scheduling work.
     *
     * @return immutable per-operation lifetime counts and rolling percentiles
     */
    public DatabaseOperationMetrics snapshot() {
        long second = Math.floorDiv(ticker.getAsLong(), TimeUnit.SECONDS.toNanos(1));
        return new DatabaseOperationMetrics(windowSeconds,
                snapshot(READ, second), snapshot(WRITE, second), snapshot(TRANSACTION, second));
    }

    /** Copies active histogram buckets while holding only one bucket lock at a time. */
    private DatabaseOperationMetrics.Operation snapshot(int kind, long currentSecond) {
        long[] totals = new long[UPPER_MILLIS.length];
        for (Bucket bucket : buckets[kind]) {
            synchronized (bucket) {
                long age = currentSecond - bucket.second;
                if (age >= 0 && age < windowSeconds) {
                    for (int i = 0; i < totals.length; i++) totals[i] += bucket.counts[i];
                }
            }
        }
        long samples = 0;
        for (long count : totals) samples += count;
        return new DatabaseOperationMetrics.Operation(successes[kind].sum(), failures[kind].sum(),
                samples, percentile(totals, samples, 50), percentile(totals, samples, 95),
                percentile(totals, samples, 99));
    }

    /** Returns the conservative histogram bound for the requested percentile. */
    private static long percentile(long[] counts, long total, int percentile) {
        if (total == 0) return -1;
        long target = (long) Math.ceil(total * (percentile / 100.0));
        long visited = 0;
        for (int index = 0; index < counts.length; index++) {
            visited += counts[index];
            if (visited >= target) return UPPER_MILLIS[index];
        }
        return Long.MAX_VALUE;
    }

    /** One independently synchronized second of histogram data. */
    private static final class Bucket {
        private long second = Long.MIN_VALUE;
        private final long[] counts = new long[UPPER_MILLIS.length];
    }
}
