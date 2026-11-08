package org.broken.arrow.library.database.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A borrowed, operation-scoped view that cannot modify database state. */
public interface ReadSession {
    /** Returns a record by its declared primary key. */
    Optional<Map<String, Object>> findById(CollectionDefinition schema, Object id);

    /** Returns at most the requested number of records. */
    List<Map<String, Object>> find(CollectionDefinition schema, RecordQuery query);

    /** Counts matching records without fetching them. */
    long count(CollectionDefinition schema, RecordQuery query);

    /** Tests whether the primary key exists. */
    default boolean exists(CollectionDefinition schema, Object id) {
        return findById(schema, id).isPresent();
    }
}
