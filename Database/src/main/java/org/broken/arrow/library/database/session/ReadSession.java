package org.broken.arrow.library.database.session;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.broken.arrow.library.database.repository.RecordQuery;
import org.broken.arrow.library.database.schema.CollectionDefinition;

/** A borrowed, operation-scoped view that cannot modify database state. */
public interface ReadSession {
    /**
     * Returns a record by its declared primary key.
     *
     * @param schema definition of the target table or collection
     * @param id stable primary-key value
     * @return computed result of the requested operation
     */
    Optional<Map<String, Object>> findById(CollectionDefinition schema, Object id);

    /**
     * Returns at most the requested number of records.
     *
     * @param schema definition of the target table or collection
     * @param query validated selection predicate and query options
     * @return collection of values matching the requested operation
     */
    List<Map<String, Object>> find(CollectionDefinition schema, RecordQuery query);

    /**
     * Counts matching records without fetching them.
     *
     * @param schema definition of the target table or collection
     * @param query validated selection predicate and query options
     * @return numeric result of the requested operation
     */
    long count(CollectionDefinition schema, RecordQuery query);

    /**
     * Computes an exact database-side sum without fetching records.
     *
     * @param schema definition of the target table or collection
     * @param query validated selection predicate and query options
     * @param field validated field name or field definition
     * @return numeric result of the requested operation
     */
    long sumLong(CollectionDefinition schema, RecordQuery query, String field);

    /**
     * Computes bounded totals for groups, ordered by descending sum and stable key.
     *
     * @param schema definition of the target table or collection
     * @param query validated selection predicate and query options
     * @param groupField column used for grouping aggregate results
     * @param sumField numeric column to sum
     * @param limit maximum number of aggregate results to return
     * @return collection of values matching the requested operation
     */
    List<org.broken.arrow.library.database.repository.GroupedLongSum> groupSumLong(
            CollectionDefinition schema, RecordQuery query, String groupField, String sumField, int limit);

    /**
     * Tests whether the primary key exists.
     *
     * @param schema definition of the target table or collection
     * @param id stable primary-key value
     * @return true if the requested condition is satisfied
     */
    default boolean exists(CollectionDefinition schema, Object id) {
        return findById(schema, id).isPresent();
    }
}
