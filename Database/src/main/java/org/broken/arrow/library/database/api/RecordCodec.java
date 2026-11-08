package org.broken.arrow.library.database.api;

import java.util.Map;

/** Explicit and allocation-conscious mapping without reflection in the database hot path. */
public interface RecordCodec<T, ID> {
    /** Returns the schema for this model. */
    CollectionDefinition schema();

    /** Returns the stable identifier. */
    ID id(T value);

    /** Encodes a model using only declared fields. May contain nullable values. */
    Map<String, Object> encode(T value);

    /** Decodes a database row, including the primary key. */
    T decode(Map<String, Object> values);
}
