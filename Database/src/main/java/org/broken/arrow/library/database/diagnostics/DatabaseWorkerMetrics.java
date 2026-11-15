package org.broken.arrow.library.database.diagnostics;

/**
 * Immutable bounded-worker occupancy and admission counters.
 *
 * @param running currently executing database tasks
 * @param queued accepted tasks waiting for a worker
 * @param activeThreads current worker thread count
 * @param completedTasks tasks executed to completion since worker startup
 * @param rejectedTasks submissions rejected by the bounded executor
 * @param queueCapacity configured upper bound for queued tasks
 * @param maximumThreads configured maximum concurrent workers
 */
public record DatabaseWorkerMetrics(
        int running,
        int queued,
        int activeThreads,
        long completedTasks,
        long rejectedTasks,
        int queueCapacity,
        int maximumThreads
) { }
