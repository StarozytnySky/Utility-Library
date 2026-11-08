package org.broken.arrow.library.database.api;

import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Per-plugin bootstrap and typed repository registry for the portable database engine.
 *
 * <p>Register every schema before calling {@link #initializeSchemasAsync()}.
 * Registration is frozen after initialization begins. Keep database-dependent
 * gameplay services disabled until that future completes successfully.</p>
 */
public final class DatabaseManager implements AutoCloseable {
    private final DatabaseClient client;
    private final Map<Class<?>, RecordRepository<?, ?>> repositories = new LinkedHashMap<>();
    private final Map<String, CollectionDefinition> schemas = new LinkedHashMap<>();
    private CompletableFuture<Void> initialization;
    private boolean closed;

    private DatabaseManager(DatabaseClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * Opens the configured backend for a plugin. This method performs blocking I/O;
     * prefer {@link #openAsync(Plugin)} when the server is already ticking.
     *
     * @param plugin owning plugin with optional bundled database.yml
     * @return the manager owning a single database client
     */
    public static DatabaseManager open(Plugin plugin) {
        return open(DatabaseConfigurationLoader.load(plugin));
    }

    /**
     * Wraps a caller-created database client and transfers its ownership to this manager.
     * Useful when selecting a custom provider or sharing programmatic configuration.
     * The caller must not close the client separately.
     *
     * @param client live client transferred to the manager
     * @return manager owning the supplied client
     */
    public static DatabaseManager withClient(DatabaseClient client) {
        return new DatabaseManager(client);
    }

    /**
     * Loads configuration on the caller thread, then establishes the database
     * connection on a virtual thread without blocking the Paper tick thread.
     *
     * @param plugin owning plugin
     * @return future completed only after provider connection is healthy
     */
    public static CompletableFuture<DatabaseManager> openAsync(Plugin plugin) {
        DatabaseConfiguration configuration;
        try {
            configuration = DatabaseConfigurationLoader.load(plugin);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        return openAsync(configuration);
    }

    /**
     * Opens, registers and initializes a plugin database without blocking the
     * calling thread. The manager is closed automatically if registration or
     * schema initialization fails. The caller owns it after successful completion.
     *
     * @param plugin owning plugin
     * @param registration callback registering all models and explicit schemas
     * @return future containing a fully initialized manager
     */
    public static CompletableFuture<DatabaseManager> bootstrapAsync(
            Plugin plugin, java.util.function.Consumer<DatabaseManager> registration) {
        Objects.requireNonNull(registration, "registration");
        return openAsync(plugin).thenCompose(manager -> {
            try {
                registration.accept(manager);
                return manager.initializeSchemasAsync()
                        .whenComplete((ignored, failure) -> {
                            if (failure != null) closeAfterFailure(manager, failure);
                        })
                        .thenApply(ignored -> manager);
            } catch (Throwable failure) {
                closeAfterFailure(manager, failure);
                return CompletableFuture.failedFuture(failure);
            }
        });
    }

    /**
     * Performs all bootstrap stages synchronously. Intended for onEnable only;
     * never call from gameplay handlers or tasks running on the Paper tick thread.
     *
     * @param plugin owning plugin
     * @param registration callback registering schemas
     * @return manager with all schemas initialized
     */
    public static DatabaseManager bootstrap(
            Plugin plugin, java.util.function.Consumer<DatabaseManager> registration) {
        Objects.requireNonNull(registration, "registration");
        DatabaseManager manager = open(plugin);
        try {
            registration.accept(manager);
            manager.initializeSchemasAsync().join();
            return manager;
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(manager, failure);
            throw failure;
        }
    }

    private static void closeAfterFailure(DatabaseManager manager, Throwable failure) {
        try {
            manager.close();
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    /**
     * Opens an already validated configuration, useful for standalone tests and
     * programmatic integration without reading plugin YAML.
     *
     * @param configuration provider settings
     * @return database manager
     */
    public static DatabaseManager open(DatabaseConfiguration configuration) {
        return new DatabaseManager(DatabaseClient.open(Objects.requireNonNull(configuration, "configuration")));
    }

    /**
     * Opens a database using virtual-thread I/O without a permanently allocated executor.
     *
     * @param configuration validated settings
     * @return future owning the manager when connection succeeds
     */
    public static CompletableFuture<DatabaseManager> openAsync(DatabaseConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        CompletableFuture<DatabaseManager> result = new CompletableFuture<>();
        Thread.ofVirtual().name("Utility-Database-Bootstrap").start(() -> {
            try {
                DatabaseManager manager = open(configuration);
                if (!result.complete(manager)) {
                    manager.close();
                }
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    /**
     * Registers an annotated Java record without repeating its table or identifier.
     * The model must declare {@link StoredCollection}.
     *
     * @param type annotated record model
     * @return typed repository
     * @param <T> model type
     * @param <ID> identifier type
     */
    public <T extends Record, ID> RecordRepository<T, ID> register(Class<T> type) {
        Objects.requireNonNull(type, "type");
        StoredCollection metadata = type.getAnnotation(StoredCollection.class);
        if (metadata == null) {
            throw new IllegalArgumentException("Missing @StoredCollection on database record: " + type.getName());
        }
        return register(type, metadata.value(), metadata.idField());
    }

    /**
     * Compiles a portable record codec and registers its repository and schema.
     *
     * @param type Java record model
     * @param collection SQL table or MongoDB collection name
     * @param idField identifier component name
     * @return registered repository for immediate injection into application services
     * @param <T> record type
     * @param <ID> identifier type
     */
    public <T extends Record, ID> RecordRepository<T, ID> register(
            Class<T> type, String collection, String idField) {
        return register(type, collection, idField, ValueCodecs.defaults());
    }

    /**
     * Registers a Java record using the caller's custom value codecs.
     *
     * @param type model type
     * @param collection storage name
     * @param idField identifier component name
     * @param codecs immutable codec registry
     * @return initialized repository descriptor (schema I/O runs later)
     * @param <T> model type
     * @param <ID> identifier type
     */
    public synchronized <T extends Record, ID> RecordRepository<T, ID> register(
            Class<T> type, String collection, String idField, ValueCodecs codecs) {
        return register(type, RecordCodecs.of(type, collection, idField, codecs));
    }

    /**
     * Registers a specialized codec, for example for a model not represented by a record.
     *
     * @param type model class
     * @param codec explicit mapping
     * @return typed repository
     * @param <T> model type
     * @param <ID> identifier type
     */
    public synchronized <T, ID> RecordRepository<T, ID> register(Class<T> type, RecordCodec<T, ID> codec) {
        ensureRegistering();
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(codec, "codec");
        if (repositories.containsKey(type)) {
            throw new IllegalArgumentException("Database model already registered: " + type.getName());
        }
        CollectionDefinition schema = codec.schema();
        if (schemas.containsKey(schema.name())) {
            throw new IllegalArgumentException("Database collection already registered: " + schema.name());
        }
        RecordRepository<T, ID> repository = client.repository(codec);
        repositories.put(type, repository);
        schemas.put(schema.name(), schema);
        return repository;
    }

    /**
     * Registers an explicit schema without requiring a Java model or repository.
     *
     * @param schema backend-neutral schema descriptor
     */
    public synchronized void registerSchema(CollectionDefinition schema) {
        ensureRegistering();
        Objects.requireNonNull(schema, "schema");
        if (schemas.putIfAbsent(schema.name(), schema) != null) {
            throw new IllegalArgumentException("Database collection already registered: " + schema.name());
        }
    }

    /**
     * Creates all registered tables/collections on a single bounded database worker.
     * Repeated calls return the same future. A failure leaves initialization failed;
     * it is never silently retried or treated as successful.
     *
     * @return shared future signalling that every registered schema exists
     */
    public synchronized CompletableFuture<Void> initializeSchemasAsync() {
        ensureOpen();
        if (initialization != null) return initialization;
        List<CollectionDefinition> snapshot = List.copyOf(new ArrayList<>(schemas.values()));
        initialization = client.createAllIfAbsentAsync(snapshot);
        return initialization;
    }

    /**
     * Returns a previously registered repository after successful schema initialization.
     *
     * @param type registered model class
     * @return typed repository
     * @param <T> model type
     * @param <ID> identifier type
     */
    @SuppressWarnings("unchecked")
    public synchronized <T, ID> RecordRepository<T, ID> repository(Class<T> type) {
        ensureOpen();
        requireReady();
        RecordRepository<?, ?> found = repositories.get(Objects.requireNonNull(type, "type"));
        if (found == null) throw new IllegalArgumentException("Database model not registered: " + type.getName());
        return (RecordRepository<T, ID>) found;
    }

    /**
     * Returns the underlying portable client for transactions and advanced queries.
     * Only use it after initialization for domain operations that require schemas.
     *
     * @return owned database client
     */
    public synchronized DatabaseClient client() {
        ensureOpen();
        requireReady();
        return client;
    }

    /**
     * Returns whether every registered schema was initialized successfully.
     *
     * @return readiness status
     */
    public synchronized boolean isReady() {
        return !closed && initialization != null && initialization.isDone()
                && !initialization.isCompletedExceptionally() && !initialization.isCancelled();
    }

    private void requireReady() {
        if (!isReady()) {
            throw new IllegalStateException("Database schema initialization has not completed successfully");
        }
    }

    private void ensureRegistering() {
        ensureOpen();
        if (initialization != null) {
            throw new IllegalStateException("Database registration is closed after schema initialization starts");
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Database manager is closed");
    }

    /**
     * Shuts down the database worker and connection resources. A manager must
     * have one owner and should be closed from the plugin disable lifecycle.
     */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        client.close();
    }
}
