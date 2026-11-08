package org.broken.arrow.library.database.codec;

import java.util.Map;

import org.broken.arrow.library.database.schema.CollectionDefinition;

/** Explicit and allocation-conscious mapping without reflection in the database hot path. */
public interface RecordCodec<T, ID> {
    /**
     * Returns the schema for this model.
     *
     * @return immutable collection schema used for persistence
     */
    CollectionDefinition schema();

    /**
     * Returns the stable identifier.
     *
     * @param value value to encode, validate, or store
     * @return identifier of the supplied record
     */
    ID id(T value);

    /**
     * Encodes a model using only declared fields. May contain nullable values.
     *
     * @param value value to encode, validate, or store
     * @return backend-compatible representation of the value
     */
    Map<String, Object> encode(T value);

    /**
     * Decodes a database row, including the primary key.
     *
     * @param values ordered values to bind or encode
     * @return decoded, strongly typed Java value
     */
    T decode(Map<String, Object> values);
}
