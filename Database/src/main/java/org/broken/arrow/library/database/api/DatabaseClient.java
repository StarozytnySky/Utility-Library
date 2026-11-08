package org.broken.arrow.library.database.api;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.broken.arrow.library.database.codec.RecordCodec;
import org.broken.arrow.library.database.codec.RecordCodecs;
import org.broken.arrow.library.database.codec.ValueCodecs;
import org.broken.arrow.library.database.config.DatabaseConfiguration;
import org.broken.arrow.library.database.config.DatabaseKind;
import org.broken.arrow.library.database.diagnostics.DatabaseWorkerMetrics;
import org.broken.arrow.library.database.internal.BoundedDatabaseWorker;
import org.broken.arrow.library.database.internal.ProviderFactory;

import org.broken.arrow.library.database.repository.RecordRepository;
import org.broken.arrow.library.database.schema.CollectionDefinition;
import org.broken.arrow.library.database.session.DatabaseWork;
import org.broken.arrow.library.database.session.ReadSession;
import org.broken.arrow.library.database.session.WriteSession;
import org.broken.arrow.library.database.spi.DatabaseProvider;

/** Thread-safe database facade; plugin code uses this type regardless of backend. */
public final class DatabaseClient implements AutoCloseable {
    private final DatabaseProvider provider;
    private final BoundedDatabaseWorker worker;
    private final Object lifecycleLock = new Object();
    private boolean closed;

    private DatabaseClient(DatabaseProvider provider, DatabaseConfiguration config) {
        this.provider = provider;
        this.worker = new BoundedDatabaseWorker(config.workerCount(), config.queueCapacity(),
                config.shutdownTimeout(), "Utility-Database-" + config.kind());
    }

    /** Starts and health-checks a provider, then creates a bounded worker. */
    public static DatabaseClient open(DatabaseConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        return new DatabaseClient(ProviderFactory.open(configuration), configuration);
    }

    /** Opens around an externally managed provider; intended for integration tests and extensions. */
    public static DatabaseClient fromProvider(DatabaseProvider provider, DatabaseConfiguration configuration) {
        Objects.requireNonNull(provider, "provider");
        if (provider.kind() != configuration.kind()) throw new IllegalArgumentException("Provider kind mismatch");
        return new DatabaseClient(provider, configuration);
    }

    /** Returns the database type chosen at startup. */
    public DatabaseKind kind() { return provider.kind(); }

    /** Describes whether the deployment offers multi-record transactions. */
    public boolean supportsTransactions() { return provider.supportsTransactions(); }

    /** Builds a high-level typed repository for the given record codec. */
    public <T, ID> RecordRepository<T, ID> repository(RecordCodec<T, ID> codec) {
        ensureOpen();
        return new RecordRepository<>(this, codec);
    }

    /**
     * Creates an auto-mapped record repository without requiring a separate codec factory call.
     * The model schema and method handles are compiled only once per repository instance.
     *
     * @param type Java record type
     * @param collection SQL table or MongoDB collection
     * @param idField identifier record component name
     * @return a repository with built-in value mapping
     * @param <T> record type
     * @param <ID> identifier type
     */
    public <T extends Record, ID> RecordRepository<T, ID> repository(
            Class<T> type, String collection, String idField) {
        return repository(RecordCodecs.of(type, collection, idField));
    }

    /**
     * Creates a record repository with explicit custom type mappings.
     *
     * @param type Java record type
     * @param collection table or collection name
     * @param idField identifier record component name
     * @param codecs immutable application-specific value codec registry
     * @return a configured repository
     * @param <T> record type
     * @param <ID> identifier type
     */
    public <T extends Record, ID> RecordRepository<T, ID> repository(
            Class<T> type, String collection, String idField, ValueCodecs codecs) {
        return repository(RecordCodecs.of(type, collection, idField, codecs));
    }

    /** Executes a blocking read; never call this on the Paper tick thread. */
    public <T> T read(DatabaseWork<ReadSession, T> work) {
        ensureOpen();
        return provider.read(work);
    }

    /** Executes a blocking multi-record transaction; prefer transactionAsync for gameplay. */
    public <T> T transaction(DatabaseWork<WriteSession, T> work) {
        ensureOpen();
        return provider.transaction(work);
    }

    /** Executes exactly one independent write in a blocking call. */
    public <T> T writeOne(DatabaseWork<WriteSession, T> work) {
        ensureOpen();
        return provider.writeOne(work);
    }

    /** Runs a read on the bounded worker; rejections complete the future exceptionally. */
    public <T> CompletableFuture<T> readAsync(DatabaseWork<ReadSession, T> work) {
        return schedule(() -> read(work));
    }

    /** Runs a multi-record transaction on the bounded worker. */
    public <T> CompletableFuture<T> transactionAsync(DatabaseWork<WriteSession, T> work) {
        return schedule(() -> transaction(work));
    }

    /** Runs one independent write on the bounded worker. */
    public <T> CompletableFuture<T> writeOneAsync(DatabaseWork<WriteSession, T> work) {
        return schedule(() -> writeOne(work));
    }

    /** Starts schema creation asynchronously; never automatically alters existing data. */
    public CompletableFuture<Void> createIfAbsentAsync(CollectionDefinition schema) {
        return schedule(() -> {
            provider.createIfAbsent(schema);
            return null;
        });
    }

    /**
     * Creates all declared tables or collections on one bounded worker task.
     *
     * <p>Creation is idempotent for existing schemas, but cross-table DDL is not
     * transactionally atomic on every backend. Callers must not start gameplay
     * services until this future has completed successfully.</p>
     *
     * @param schemas validated schemas prepared during bootstrap
     * @return future completed only when every schema was checked or created
     */
    public CompletableFuture<Void> createAllIfAbsentAsync(
            java.util.Collection<CollectionDefinition> schemas) {
        java.util.List<CollectionDefinition> snapshot = java.util.List.copyOf(
                Objects.requireNonNull(schemas, "schemas"));
        return schedule(() -> {
            for (CollectionDefinition schema : snapshot) {
                provider.createIfAbsent(schema);
            }
            return null;
        });
    }

    /** Returns current worker capacity and queue diagnostics without user data. */
    public DatabaseWorkerMetrics snapshot() { return worker.snapshot(); }

    private <T> CompletableFuture<T> schedule(java.util.concurrent.Callable<T> action) {
        synchronized (lifecycleLock) {
            if (closed) return CompletableFuture.failedFuture(
                    new DatabaseFailure(DatabaseFailure.Reason.CLOSED, "Database client is closed"));
            return worker.submit(action);
        }
    }

    private void ensureOpen() {
        synchronized (lifecycleLock) {
            if (closed) throw new DatabaseFailure(DatabaseFailure.Reason.CLOSED, "Database client is closed");
        }
    }

    /**
     * Drains the bounded worker and releases its provider after every active transaction finishes.
     * A shutdown timeout leaves running transactions undisturbed; the pool is closed later.
     */
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
        }
        worker.close();
        if (worker.isTerminated()) {
            provider.close();
            return;
        }
        // A database driver may ignore interrupts while awaiting network I/O.
        // Release its pool only when outstanding operations have definitely ended.
        Thread.ofVirtual().name("Utility-Database-Close").start(() -> {
            worker.awaitFullTermination();
            provider.close();
        });
        throw new DatabaseFailure(DatabaseFailure.Reason.OPERATION,
                "Database shutdown timed out; provider will close after the active operations finish");
    }
}
