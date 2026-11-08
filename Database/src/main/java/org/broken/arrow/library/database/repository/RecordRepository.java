package org.broken.arrow.library.database.repository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.broken.arrow.library.database.api.DatabaseClient;
import org.broken.arrow.library.database.codec.RecordCodec;
import org.broken.arrow.library.database.schema.CollectionDefinition;
import org.broken.arrow.library.database.session.ReadSession;
import org.broken.arrow.library.database.session.WriteSession;

/** Typed CRUD facade backed by the provider-independent record/session contracts. */
public final class RecordRepository<T, ID> {
    private final DatabaseClient database;
    private final RecordCodec<T, ID> codec;

    /**
     * Creates a typed repository for a precompiled codec and database client.
     * Prefer {@link DatabaseClient#repository(RecordCodec)} for normal registration.
     *
     * @param database owning database client
     * @param codec verified record mapping
     */
    public RecordRepository(DatabaseClient database, RecordCodec<T, ID> codec) {
        this.database = Objects.requireNonNull(database, "database");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /** Returns the immutable collection mapping. */
    public CollectionDefinition schema() { return codec.schema(); }

    /** Performs a non-blocking-by-caller lookup on the shared database worker. */
    public CompletableFuture<Optional<T>> findByIdAsync(ID id) {
        return database.readAsync(session -> findById(session, id));
    }

    /** Finds an entity using an already borrowed read or write session. */
    public Optional<T> findById(ReadSession session, ID id) {
        Objects.requireNonNull(id, "id");
        return session.findById(schema(), id).map(codec::decode);
    }

    /** Executes an immutable query, returning bounded mapped results. */
    public CompletableFuture<List<T>> findAsync(RecordQuery query) {
        return database.readAsync(session -> find(session, query));
    }

    /** Executes an immutable query within a caller-owned session. */
    public List<T> find(ReadSession session, RecordQuery query) {
        return session.find(schema(), query).stream().map(codec::decode).toList();
    }

    /** Counts rows without materializing entities. */
    public CompletableFuture<Long> countAsync(RecordQuery query) {
        return database.readAsync(session -> session.count(schema(), query));
    }

    /** Inserts one model; duplicate keys propagate as explicit conflicts. */
    public CompletableFuture<Void> insertAsync(T model) {
        return database.writeOneAsync(session -> {
            insert(session, model);
            return null;
        });
    }

    /** Inserts using an existing write session; can participate in a larger transaction. */
    public void insert(WriteSession session, T model) {
        Map<String, Object> values = codec.encode(model);
        checkModel(model, values);
        session.insert(schema(), values);
    }

    /** Performs a portable upsert with one independent write. */
    public CompletableFuture<Void> upsertAsync(T model) {
        return database.writeOneAsync(session -> {
            upsert(session, model);
            return null;
        });
    }

    /** Performs a portable upsert in the current transaction. */
    public void upsert(WriteSession session, T model) {
        Map<String, Object> values = codec.encode(model);
        checkModel(model, values);
        session.upsert(schema(), values);
    }

    /** Replaces declared mutable fields and returns whether the record exists. */
    public CompletableFuture<Boolean> updateAsync(T model) {
        return database.writeOneAsync(session -> update(session, model));
    }

    /** Updates within the caller's transaction, preserving its commit boundary. */
    public boolean update(WriteSession session, T model) {
        Map<String, Object> values = codec.encode(model);
        checkModel(model, values);
        return session.update(schema(), values);
    }

    /** Deletes one record without a read-before-write race. */
    public CompletableFuture<Boolean> deleteByIdAsync(ID id) {
        return database.writeOneAsync(session -> session.deleteById(schema(), id));
    }

    /** Atomically decrements only if the record has enough of the specified integer value. */
    public CompletableFuture<Boolean> decrementIfEnoughAsync(ID id, String field, long amount) {
        return database.writeOneAsync(session -> session.decrementIfEnough(schema(), id, field, amount));
    }

    /** Atomically increments or decrements an existing integer field. */
    public CompletableFuture<Boolean> incrementAsync(ID id, String field, long amount) {
        return database.writeOneAsync(session -> session.increment(schema(), id, field, amount));
    }

    /** Atomically inserts multiple records using provider-specific batching. */
    public CompletableFuture<Void> insertBatchAsync(List<T> models, int chunkSize) {
        Objects.requireNonNull(models, "models");
        if (chunkSize < 1 || chunkSize > 10_000) throw new IllegalArgumentException("Invalid batch size");
        List<Map<String, Object>> encoded = models.stream().map(model -> {
            Map<String, Object> values = codec.encode(model);
            checkModel(model, values);
            return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(values));
        }).toList();
        return database.transactionAsync(session -> {
            session.insertBatch(schema(), encoded, chunkSize);
            return null;
        });
    }

    /** Inserts if absent, returning false on a unique-key conflict. */
    public CompletableFuture<Boolean> insertIfAbsentAsync(T model) {
        return database.writeOneAsync(session -> {
            Map<String, Object> values = codec.encode(model);
            checkModel(model, values);
            return session.insertIfAbsent(schema(), values);
        });
    }

    /** Checks existence without materializing the record on the caller's thread. */
    public CompletableFuture<Boolean> existsAsync(ID id) {
        return database.readAsync(session -> session.exists(schema(), id));
    }

    /** Atomically changes an integer value if it matches the expected version/value. */
    public CompletableFuture<Boolean> compareAndSetAsync(ID id, String field, long expected, long updated) {
        return database.writeOneAsync(session -> session.compareAndSet(schema(), id, field, expected, updated));
    }

    /** Ensures the declared collection exists using the currently selected provider. */
    public CompletableFuture<Void> ensureSchemaAsync() {
        return database.createIfAbsentAsync(schema());
    }

    /** Checks existence within an already borrowed read or transaction session. */
    public boolean exists(ReadSession session, ID id) {
        return session.exists(schema(), Objects.requireNonNull(id, "id"));
    }

    /** Counts matches within an already borrowed read or transaction session. */
    public long count(ReadSession session, RecordQuery query) {
        return session.count(schema(), Objects.requireNonNull(query, "query"));
    }

    /** Deletes one model within an existing transaction, without opening another connection. */
    public boolean deleteById(WriteSession session, ID id) {
        return session.deleteById(schema(), Objects.requireNonNull(id, "id"));
    }

    /** Atomically decrements a balance within the caller's transaction. */
    public boolean decrementIfEnough(WriteSession session, ID id, String field, long amount) {
        return session.decrementIfEnough(schema(), Objects.requireNonNull(id, "id"), field, amount);
    }

    /** Atomically increments a balance within the caller's transaction. */
    public boolean increment(WriteSession session, ID id, String field, long amount) {
        return session.increment(schema(), Objects.requireNonNull(id, "id"), field, amount);
    }

    /** Compares and swaps a long value under the caller's existing transaction. */
    public boolean compareAndSet(WriteSession session, ID id, String field, long expected, long updated) {
        return session.compareAndSet(schema(), Objects.requireNonNull(id, "id"), field, expected, updated);
    }

    /** Inserts a complete model unless the identifier exists, in the current transaction. */
    public boolean insertIfAbsent(WriteSession session, T model) {
        Map<String, Object> values = codec.encode(model);
        checkModel(model, values);
        return session.insertIfAbsent(schema(), values);
    }

    private void checkModel(T model, Map<String, Object> values) {
        Objects.requireNonNull(values, "encoded values");
        schema().validateValues(values, true);
        if (!Objects.equals(codec.id(model), values.get(schema().idField()))) {
            throw new IllegalArgumentException("Record identifier does not match codec identifier");
        }
    }
}
