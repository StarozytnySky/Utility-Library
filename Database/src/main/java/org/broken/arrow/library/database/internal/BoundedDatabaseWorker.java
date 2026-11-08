package org.broken.arrow.library.database.internal;

import org.broken.arrow.library.database.api.DatabaseFailure;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Instance-owned bounded blocking worker; SQL never falls back to the caller thread. */
public final class BoundedDatabaseWorker implements AutoCloseable {
    /** Thread-safe snapshot of worker utilization. */
    public record Snapshot(int running, int queued, int activeThreads, long completedTasks) { }

    private final ThreadPoolExecutor executor;
    private final Duration shutdownTimeout;
    private volatile boolean terminated;

    /** Creates a fixed worker with a hard queue limit. */
    public BoundedDatabaseWorker(int threads, int capacity, Duration shutdownTimeout, String threadPrefix) {
        if (threads < 1 || capacity < 1) throw new IllegalArgumentException("Invalid worker limits");
        this.shutdownTimeout = Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        AtomicInteger index = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, threadPrefix + "-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        executor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    /** Submits blocking work and makes saturation an observable exceptional future. */
    public <T> CompletableFuture<T> submit(Callable<T> work) {
        Job<T> job = new Job<>(work);
        try {
            executor.execute(job);
        } catch (RejectedExecutionException rejected) {
            job.reject(new DatabaseFailure(DatabaseFailure.Reason.OVERLOADED,
                    "Database worker is closed or its queue is full", rejected));
        }
        return job.future;
    }

    /** Returns an immutable monitoring snapshot. */
    public Snapshot snapshot() {
        return new Snapshot(executor.getActiveCount(), executor.getQueue().size(),
                executor.getPoolSize(), executor.getCompletedTaskCount());
    }

    /** Stops new submissions and waits for writes without interrupting active COMMIT calls. */
    @Override
    public void close() {
        executor.shutdown();
        boolean interrupted = false;
        try {
            executor.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException failure) {
            interrupted = true;
        } finally {
            terminated = executor.isTerminated();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Returns whether every running database operation has finished. */
    public boolean isTerminated() { return terminated; }

    /** Waits until every active operation has stopped, preserving interruption state. */
    public void awaitFullTermination() {
        boolean interrupted = false;
        while (!executor.isTerminated()) {
            try {
                executor.awaitTermination(1, TimeUnit.DAYS);
            } catch (InterruptedException failure) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static final class Job<T> implements Runnable {
        private final CompletableFuture<T> future = new CompletableFuture<>();
        private final Callable<T> callable;

        private Job(Callable<T> callable) { this.callable = Objects.requireNonNull(callable); }

        private void reject(Throwable failure) { future.completeExceptionally(failure); }

        @Override
        public void run() {
            if (future.isDone()) return;
            try {
                future.complete(callable.call());
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        }
    }
}
