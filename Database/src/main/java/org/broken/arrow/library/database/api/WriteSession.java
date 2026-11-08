package org.broken.arrow.library.database.api;

import java.util.Map;
import java.util.List;

/** A borrowed write view; only its owner commits/rolls back a transaction. */
public interface WriteSession extends ReadSession {
    /** Inserts one complete record; duplicate identifiers must fail explicitly. */
    void insert(CollectionDefinition schema, Map<String, Object> record);

    /** Inserts or replaces declared mutable fields, never the identifier. */
    void upsert(CollectionDefinition schema, Map<String, Object> record);

    /** Replaces all non-key fields of an existing record. Returns false if absent. */
    boolean update(CollectionDefinition schema, Map<String, Object> record);

    /** Deletes one record, reporting whether it existed. */
    boolean deleteById(CollectionDefinition schema, Object id);

    /** Atomically adds a signed integer delta to a declared numeric field. */
    boolean increment(CollectionDefinition schema, Object id, String field, long amount);

    /** Atomically subtracts a non-negative amount only if sufficient value remains. */
    boolean decrementIfEnough(CollectionDefinition schema, Object id, String field, long amount);
    /** Atomically inserts a new record unless its primary key already exists. */
    boolean insertIfAbsent(CollectionDefinition schema, Map<String, Object> record);

    /** Inserts all records under the current transaction; implementations should batch I/O. */
    void insertBatch(CollectionDefinition schema, List<Map<String, Object>> records, int chunkSize);

    /** Performs a compare-and-set update on an integer field, if the expected value matches. */
    boolean compareAndSet(CollectionDefinition schema, Object id, String field, long expected, long updated);

}
