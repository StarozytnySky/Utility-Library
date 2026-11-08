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

    /**
     * Returns the immutable collection mapping.
     *
     * @return immutable collection schema used for persistence
     */
    public CollectionDefinition schema() { return codec.schema(); }

    /**
     * Performs a non-blocking-by-caller lookup on the shared database worker.
     *
     * @param id stable primary-key value
     * @return future containing the record if the identifier exists
     */
    public CompletableFuture<Optional<T>> findByIdAsync(ID id) {
        return database.readAsync(session -> findById(session, id));
    }

    /**
     * Finds an entity using an already borrowed read or write session.
     *
     * @param session active database session sharing the current transaction
     * @param id stable primary-key value
     * @return computed result of the requested operation
     */
    public Optional<T> findById(ReadSession session, ID id) {
        Objects.requireNonNull(id, "id");
        return session.findById(schema(), id).map(codec::decode);
    }

    /**
     * Executes an immutable query, returning bounded mapped results.
     *
     * @param query validated selection predicate and query options
     * @return future containing the bounded page of matching records
     */
    public CompletableFuture<List<T>> findAsync(RecordQuery query) {
        return database.readAsync(session -> find(session, query));
    }

    /**
     * Executes an immutable query within a caller-owned session.
     *
     * @param session active database session sharing the current transaction
     * @param query validated selection predicate and query options
     * @return collection of values matching the requested operation
     */
    public List<T> find(ReadSession session, RecordQuery query) {
        return session.find(schema(), query).stream().map(codec::decode).toList();
    }

    /**
     * Counts rows without materializing entities.
     *
     * @param query validated selection predicate and query options
     * @return future containing the number of matching records
     */
    public CompletableFuture<Long> countAsync(RecordQuery query) {
        return database.readAsync(session -> session.count(schema(), query));
    }

    /**
     * Computes the exact sum of a long field directly on the selected backend.
     *
     * @param query validated selection predicate and query options
     * @param field validated field name or field definition
     * @return future containing the sum of the selected numeric values
     */
    public CompletableFuture<Long> sumLongAsync(RecordQuery query, String field) {
        return database.readAsync(session -> session.sumLong(schema(), query, field));
    }

    /**
     * Computes bounded grouped totals on the backend; appropriate for ranking pages.
     *
     * @param query validated selection predicate and query options
     * @param groupField column used for grouping aggregate results
     * @param sumField numeric column to sum
     * @param limit maximum number of aggregate results to return
     * @return future containing grouped sums computed by the backend
     */
    public CompletableFuture<List<GroupedLongSum>> groupSumLongAsync(
            RecordQuery query, String groupField, String sumField, int limit) {
        return database.readAsync(session -> session.groupSumLong(
                schema(), query, groupField, sumField, limit));
    }

    /**
     * Executes a transactional conditional update and returns the affected-row count.
     *
     * @param query validated selection predicate and query options
     * @param changes validated field assignments to apply atomically
     * @return future containing the number of matching rows updated
     */
    public CompletableFuture<Long> updateMatchingAsync(RecordQuery query, Map<String, Object> changes) {
        Objects.requireNonNull(query, "query");
        Map<String, Object> snapshot = java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(Objects.requireNonNull(changes, "changes")));
        return database.transactionAsync(session -> session.updateMatching(schema(), query, snapshot));
    }

    /**
     * Updates exactly the expected number of records; mismatch rolls back the transaction.
     *
     * @param query validated selection predicate and query options
     * @param changes validated field assignments to apply atomically
     * @param expected required current value or number of affected rows
     * @return future containing the number of updates after enforcing the expected count
     */
    public CompletableFuture<Void> updateExactlyAsync(RecordQuery query,
            Map<String, Object> changes, long expected) {
        Objects.requireNonNull(query, "query");
        Map<String, Object> snapshot = java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(Objects.requireNonNull(changes, "changes")));
        return database.transactionAsync(session -> {
            session.updateExactly(schema(), query, snapshot, expected);
            return null;
        });
    }

    /**
     * Performs a conditional update within a caller-owned multi-table transaction.
     *
     * @param session active database session sharing the current transaction
     * @param query validated selection predicate and query options
     * @param changes validated field assignments to apply atomically
     * @return numeric result of the requested operation
     */
    public long updateMatching(WriteSession session, RecordQuery query, Map<String, Object> changes) {
        return session.updateMatching(schema(), query, changes);
    }

    /**
     * Enforces an exact conditional update count within a transaction.
     *
     * @param session active database session sharing the current transaction
     * @param query validated selection predicate and query options
     * @param changes validated field assignments to apply atomically
     * @param expected required current value or number of affected rows
     */
    public void updateExactly(WriteSession session, RecordQuery query,
            Map<String, Object> changes, long expected) {
        session.updateExactly(schema(), query, changes, expected);
    }

    /**
     * Deletes matching records on the shared worker, in a single database transaction.
     * The predicate must contain at least one condition; ordering/page size do not limit deletion.
     *
     * @param query non-empty selection predicate
     * @return future containing the exact number of deleted records
     */
    public CompletableFuture<Long> deleteMatchingAsync(RecordQuery query) {
        Objects.requireNonNull(query, "query");
        return database.transactionAsync(session -> deleteMatching(session, query));
    }

    /**
     * Deletes exactly the required number of records or rolls back the transaction.
     *
     * @param query non-empty selection predicate
     * @param expected exact number of records to remove
     * @return future completed after the transaction commits
     */
    public CompletableFuture<Void> deleteExactlyAsync(RecordQuery query, long expected) {
        Objects.requireNonNull(query, "query");
        return database.transactionAsync(session -> {
            deleteExactly(session, query, expected);
            return null;
        });
    }

    /**
     * Deletes matching records within a caller-managed multi-collection transaction.
     *
     * @param session borrowed transaction session
     * @param query non-empty selection predicate
     * @return number of deleted rows or documents
     */
    public long deleteMatching(WriteSession session, RecordQuery query) {
        Objects.requireNonNull(session, "session");
        return session.deleteMatching(schema(), Objects.requireNonNull(query, "query"));
    }

    /**
     * Requires an exact deletion count within a caller-managed transaction.
     *
     * @param session borrowed transaction session
     * @param query non-empty selection predicate
     * @param expected exact number of records to delete
     */
    public void deleteExactly(WriteSession session, RecordQuery query, long expected) {
        Objects.requireNonNull(session, "session");
        session.deleteExactly(schema(), Objects.requireNonNull(query, "query"), expected);
    }

    /**
     * Inserts one model; duplicate keys propagate as explicit conflicts.
     *
     * @param model domain record to encode or persist
     * @return future completed after the insert has committed
     */
    public CompletableFuture<Void> insertAsync(T model) {
        return database.writeOneAsync(session -> {
            insert(session, model);
            return null;
        });
    }

    /**
     * Inserts using an existing write session; can participate in a larger transaction.
     *
     * @param session active database session sharing the current transaction
     * @param model domain record to encode or persist
     */
    public void insert(WriteSession session, T model) {
        Map<String, Object> values = codec.encode(model);
        checkModel(model, values);
        session.insert(schema(), values);
    }

    /**
     * Performs a portable upsert with one independent write.
     *
     * @param model domain record to encode or persist
     * @return future completed after the upsert has committed
     */
    public CompletableFuture<Void> upsertAsync(T model) {
        return database.writeOneAsync(session -> {
            upsert(session, model);
            return null;
        });
    }

    /**
     * Performs a portable upsert in the current transaction.
     *
     * @param session active database session sharing the current transaction
     * @param model domain record to encode or persist
     */
    public void upsert(WriteSession session, T model) {
        Map<String, Object> values = codec.encode(model);
        checkModel(model, values);
        session.upsert(schema(), values);
    }

    /**
     * Replaces declared mutable fields and returns whether the record exists.
     *
     * @param model domain record to encode or persist
     * @return future containing whether the record was updated
     */
    public CompletableFuture<Boolean> updateAsync(T model) {
        return database.writeOneAsync(session -> update(session, model));
    }

    /**
     * Updates within the caller's transaction, preserving its commit boundary.
     *
     * @param session active database session sharing the current transaction
     * @param model domain record to encode or persist
     * @return true if the requested condition is satisfied
     */
    public boolean update(WriteSession session, T model) {
        Map<String, Object> values = codec.encode(model);
        checkModel(model, values);
        return session.update(schema(), values);
    }

    /**
     * Deletes one record without a read-before-write race.
     *
     * @param id stable primary-key value
     * @return future containing whether the record was deleted
     */
    public CompletableFuture<Boolean> deleteByIdAsync(ID id) {
        return database.writeOneAsync(session -> session.deleteById(schema(), id));
    }

    /**
     * Atomically decrements only if the record has enough of the specified integer value.
     *
     * @param id stable primary-key value
     * @param field validated field name or field definition
     * @param amount signed numeric adjustment for an atomic update
     * @return future containing whether the conditional decrement succeeded
     */
    public CompletableFuture<Boolean> decrementIfEnoughAsync(ID id, String field, long amount) {
        return database.writeOneAsync(session -> session.decrementIfEnough(schema(), id, field, amount));
    }

    /**
     * Atomically increments or decrements an existing integer field.
     *
     * @param id stable primary-key value
     * @param field validated field name or field definition
     * @param amount signed numeric adjustment for an atomic update
     * @return future containing whether the bounded atomic update succeeded
     */
    public CompletableFuture<Boolean> incrementAsync(ID id, String field, long amount) {
        return database.writeOneAsync(session -> session.increment(schema(), id, field, amount));
    }

    /**
     * Atomically inserts multiple records using provider-specific batching.
     *
     * @param models value of models used by this operation
     * @param chunkSize maximum number of operations in each JDBC batch
     * @return future completed after all batch entries commit atomically
     */
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

    /**
     * Inserts if absent, returning false on a unique-key conflict.
     *
     * @param model domain record to encode or persist
     * @return future containing true when a new record was inserted
     */
    public CompletableFuture<Boolean> insertIfAbsentAsync(T model) {
        return database.writeOneAsync(session -> {
            Map<String, Object> values = codec.encode(model);
            checkModel(model, values);
            return session.insertIfAbsent(schema(), values);
        });
    }

    /**
     * Checks existence without materializing the record on the caller's thread.
     *
     * @param id stable primary-key value
     * @return future containing whether the identifier exists
     */
    public CompletableFuture<Boolean> existsAsync(ID id) {
        return database.readAsync(session -> session.exists(schema(), id));
    }

    /**
     * Atomically changes an integer value if it matches the expected version/value.
     *
     * @param id stable primary-key value
     * @param field validated field name or field definition
     * @param expected required current value or number of affected rows
     * @param updated replacement value on successful compare-and-set
     * @return future containing whether the expected value was replaced
     */
    public CompletableFuture<Boolean> compareAndSetAsync(ID id, String field, long expected, long updated) {
        return database.writeOneAsync(session -> session.compareAndSet(schema(), id, field, expected, updated));
    }

    /**
     * Ensures the declared collection exists using the currently selected provider.
     *
     * @return future containing the outcome of the requested operation
     */
    public CompletableFuture<Void> ensureSchemaAsync() {
        return database.createIfAbsentAsync(schema());
    }

    /**
     * Checks existence within an already borrowed read or transaction session.
     *
     * @param session active database session sharing the current transaction
     * @param id stable primary-key value
     * @return true if the requested condition is satisfied
     */
    public boolean exists(ReadSession session, ID id) {
        return session.exists(schema(), Objects.requireNonNull(id, "id"));
    }

    /**
     * Counts matches within an already borrowed read or transaction session.
     *
     * @param session active database session sharing the current transaction
     * @param query validated selection predicate and query options
     * @return numeric result of the requested operation
     */
    public long count(ReadSession session, RecordQuery query) {
        return session.count(schema(), Objects.requireNonNull(query, "query"));
    }

    /**
     * Deletes one model within an existing transaction, without opening another connection.
     *
     * @param session active database session sharing the current transaction
     * @param id stable primary-key value
     * @return true if the requested condition is satisfied
     */
    public boolean deleteById(WriteSession session, ID id) {
        return session.deleteById(schema(), Objects.requireNonNull(id, "id"));
    }

    /**
     * Atomically decrements a balance within the caller's transaction.
     *
     * @param session active database session sharing the current transaction
     * @param id stable primary-key value
     * @param field validated field name or field definition
     * @param amount signed numeric adjustment for an atomic update
     * @return true if the requested condition is satisfied
     */
    public boolean decrementIfEnough(WriteSession session, ID id, String field, long amount) {
        return session.decrementIfEnough(schema(), Objects.requireNonNull(id, "id"), field, amount);
    }

    /**
     * Atomically increments a balance within the caller's transaction.
     *
     * @param session active database session sharing the current transaction
     * @param id stable primary-key value
     * @param field validated field name or field definition
     * @param amount signed numeric adjustment for an atomic update
     * @return true if the requested condition is satisfied
     */
    public boolean increment(WriteSession session, ID id, String field, long amount) {
        return session.increment(schema(), Objects.requireNonNull(id, "id"), field, amount);
    }

    /**
     * Compares and swaps a long value under the caller's existing transaction.
     *
     * @param session active database session sharing the current transaction
     * @param id stable primary-key value
     * @param field validated field name or field definition
     * @param expected required current value or number of affected rows
     * @param updated replacement value on successful compare-and-set
     * @return true if the requested condition is satisfied
     */
    public boolean compareAndSet(WriteSession session, ID id, String field, long expected, long updated) {
        return session.compareAndSet(schema(), Objects.requireNonNull(id, "id"), field, expected, updated);
    }

    /**
     * Inserts a complete model unless the identifier exists, in the current transaction.
     *
     * @param session active database session sharing the current transaction
     * @param model domain record to encode or persist
     * @return true if the record was inserted, false if its identifier already exists
     */
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
