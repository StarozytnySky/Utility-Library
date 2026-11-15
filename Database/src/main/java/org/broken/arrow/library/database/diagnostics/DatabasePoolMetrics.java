package org.broken.arrow.library.database.diagnostics;

/**
 * Passive, read-only snapshot of a HikariCP JDBC pool.
 *
 * <p>Occupancy counters may move while assembled. Acquisition percentile bounds
 * measure the time spent borrowing a JDBC connection from Hikari, including
 * blocked waiters, but exclude JDBC statements. Unsupported backends return
 * no pool metrics at all; a manually created 5-field test snapshot omits timing.</p>
 *
 * @param active currently borrowed connections
 * @param idle idle connections
 * @param total total physical connections owned by the pool
 * @param waiting threads waiting for a borrowed connection
 * @param maximum configured pool size
 * @param acquisitionWindowSeconds configured sliding window for acquisition histograms
 * @param acquisitions measured acquisition successes/failures and p50/p95/p99 bounds
 */
public record DatabasePoolMetrics(int active, int idle, int total,
                                  int waiting, int maximum,
                                  int acquisitionWindowSeconds,
                                  DatabaseOperationMetrics.Operation acquisitions) {

    /**
     * Preserves the simple occupancy-only constructor used by existing consumers.
     *
     * @param active connections currently borrowed
     * @param idle idle connections
     * @param total total connections
     * @param waiting threads waiting for a connection
     * @param maximum configured maximum pool size
     */
    public DatabasePoolMetrics(int active, int idle, int total, int waiting, int maximum) {
        this(active, idle, total, waiting, maximum, 0, null);
    }
}
