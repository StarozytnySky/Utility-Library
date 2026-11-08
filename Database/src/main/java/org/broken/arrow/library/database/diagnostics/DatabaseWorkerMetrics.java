package org.broken.arrow.library.database.diagnostics;

/**
 * Immutable snapshot of database worker utilization.
 *
 * @param running currently executing database tasks
 * @param queued tasks awaiting a worker
 * @param activeThreads current worker thread count
 * @param completedTasks total completed database tasks
 */
public record DatabaseWorkerMetrics(
        int running,
        int queued,
        int activeThreads,
        long completedTasks
) { }
