package org.broken.arrow.library.database.diagnostics;

/**
 * Immutable, allocation-bounded monitoring snapshot from the DatabaseClient provider boundary.
 *
 * <p>Durations include acquiring a provider connection and executing the provider callback,
 * but exclude time spent waiting in the DatabaseClient worker queue. They do not represent
 * one SQL statement or one MongoDB round trip: a callback may perform several queries.</p>
 *
 * @param windowSeconds configured rolling time horizon
 * @param reads blocking read callbacks (including those invoked by readAsync)
 * @param writes single-write callbacks (including those invoked by writeOneAsync)
 * @param transactions multi-record transaction callbacks (including transactionAsync)
 */
public record DatabaseOperationMetrics(int windowSeconds, Operation reads,
                                       Operation writes, Operation transactions) {

    /**
     * One operation family's lifetime counters and recent completed-call durations.
     *
     * @param successfulTotal callbacks completed successfully since client creation
     * @param failedTotal callbacks that threw a Throwable since client creation
     * @param samples completed callbacks still inside the rolling window
     * @param p50UpperMillis inclusive upper bound for the 50th percentile, or -1 if empty
     * @param p95UpperMillis inclusive upper bound for the 95th percentile, or -1 if empty
     * @param p99UpperMillis inclusive upper bound for the 99th percentile, or -1 if empty
     */
    public record Operation(long successfulTotal, long failedTotal, long samples,
                            long p50UpperMillis, long p95UpperMillis, long p99UpperMillis) {

        /** @return total number of executed provider callbacks, including failures */
        public long completedTotal() {
            return successfulTotal + failedTotal;
        }
    }
}
