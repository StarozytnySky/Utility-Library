package org.broken.arrow.library.database.session;

import java.util.List;

import java.util.Map;
import org.broken.arrow.library.database.schema.CollectionDefinition;

/** A borrowed write view; only its owner commits/rolls back a transaction. */
public interface WriteSession extends ReadSession {
    /**
     * Inserts one complete record; duplicate identifiers must fail explicitly.
     *
     * @param schema definition of the target table or collection
     * @param record validated database record to persist
     */
    void insert(CollectionDefinition schema, Map<String, Object> record);

    /**
     * Inserts or replaces declared mutable fields, never the identifier.
     *
     * @param schema definition of the target table or collection
     * @param record validated database record to persist
     */
    void upsert(CollectionDefinition schema, Map<String, Object> record);

    /**
     * Replaces all non-key fields of an existing record. Returns false if absent.
     *
     * @param schema definition of the target table or collection
     * @param record validated database record to persist
     * @return true if the requested condition is satisfied
     */
    boolean update(CollectionDefinition schema, Map<String, Object> record);

    /**
     * Deletes one record, reporting whether it existed.
     *
     * @param schema definition of the target table or collection
     * @param id stable primary-key value
     * @return true if the requested condition is satisfied
     */
    boolean deleteById(CollectionDefinition schema, Object id);

    /**
     * Atomically adds a signed integer delta to a declared numeric field.
     *
     * @param schema definition of the target table or collection
     * @param id stable primary-key value
     * @param field validated field name or field definition
     * @param amount signed numeric adjustment for an atomic update
     * @return true if the requested condition is satisfied
     */
    boolean increment(CollectionDefinition schema, Object id, String field, long amount);

    /**
     * Atomically subtracts a non-negative amount only if sufficient value remains.
     *
     * @param schema definition of the target table or collection
     * @param id stable primary-key value
     * @param field validated field name or field definition
     * @param amount signed numeric adjustment for an atomic update
     * @return true if the requested condition is satisfied
     */
    boolean decrementIfEnough(CollectionDefinition schema, Object id, String field, long amount);
    /**
     * Atomically inserts a new record unless its primary key already exists.
     *
     * @param schema definition of the target table or collection
     * @param record validated database record to persist
     * @return true if the record was inserted, false if its identifier already exists
     */
    boolean insertIfAbsent(CollectionDefinition schema, Map<String, Object> record);

    /**
     * Inserts all records under the current transaction; implementations should batch I/O.
     *
     * @param schema definition of the target table or collection
     * @param records records to write as a batch
     * @param chunkSize maximum number of operations in each JDBC batch
     */
    void insertBatch(CollectionDefinition schema, List<Map<String, Object>> records, int chunkSize);

    /**
     * Updates matching rows without loading them, returning the provider's affected-row count.
     *
     * @param schema definition of the target table or collection
     * @param query validated selection predicate and query options
     * @param changes validated field assignments to apply atomically
     * @return numeric result of the requested operation
     */
    long updateMatching(CollectionDefinition schema, org.broken.arrow.library.database.repository.RecordQuery query,
                        Map<String, Object> changes);

    /**
     * Updates exactly the expected number of matching rows or aborts the transaction.
     *
     * @param schema definition of the target table or collection
     * @param query validated selection predicate and query options
     * @param changes validated field assignments to apply atomically
     * @param expected required current value or number of affected rows
     */
    default void updateExactly(CollectionDefinition schema,
            org.broken.arrow.library.database.repository.RecordQuery query,
            Map<String, Object> changes, long expected) {
        if (expected < 0) throw new IllegalArgumentException("Expected row count must be non-negative");
        long changed = updateMatching(schema, query, changes);
        if (changed != expected) {
            throw new org.broken.arrow.library.database.api.DatabaseFailure(
                    org.broken.arrow.library.database.api.DatabaseFailure.Reason.CONFLICT,
                    "Conditional mutation affected " + changed + " records, expected " + expected);
        }
    }

    /**
     * Deletes rows matching a non-empty, schema-validated predicate within the current transaction.
     * The query's pagination and ordering fields do not constrain the mutation.
     *
     * @param schema definition of the target table or collection
     * @param query predicate identifying records to delete; at least one condition is required
     * @return number of records deleted
     */
    long deleteMatching(CollectionDefinition schema,
                        org.broken.arrow.library.database.repository.RecordQuery query);

    /**
     * Enforces an expected deletion count and aborts the surrounding transaction on mismatch.
     *
     * @param schema definition of the target table or collection
     * @param query non-empty predicate identifying records to delete
     * @param expected exact number of records that must be deleted
     */
    default void deleteExactly(CollectionDefinition schema,
            org.broken.arrow.library.database.repository.RecordQuery query, long expected) {
        if (expected < 0) throw new IllegalArgumentException("Expected count must be non-negative");
        long affected = deleteMatching(schema, query);
        if (affected != expected) {
            throw new org.broken.arrow.library.database.api.DatabaseFailure(
                    org.broken.arrow.library.database.api.DatabaseFailure.Reason.CONFLICT,
                    "Conditional deletion affected " + affected + " records, expected " + expected);
        }
    }

    /**
     * Performs a compare-and-set update on an integer field, if the expected value matches.
     *
     * @param schema definition of the target table or collection
     * @param id stable primary-key value
     * @param field validated field name or field definition
     * @param expected required current value or number of affected rows
     * @param updated replacement value on successful compare-and-set
     * @return true if the requested condition is satisfied
     */
    boolean compareAndSet(CollectionDefinition schema, Object id, String field, long expected, long updated);

}
