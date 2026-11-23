package org.broken.arrow.library.database.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.broken.arrow.library.database.schema.CollectionDefinition;
import org.broken.arrow.library.database.schema.CompositeKey;
import org.broken.arrow.library.database.schema.FieldType;
import org.broken.arrow.library.database.schema.Identifiers;

/** Immutable AND query usable with SQL prepared statements and MongoDB BSON filters. */
public record RecordQuery(List<Condition> conditions, String orderBy, boolean descending,
                          int limit, int offset, Object afterPrimaryKey) {
    /**
     * Creates a query without a cursor (compatible with the existing five-argument API).
     *
     * @param conditions predicates joined by AND
     * @param orderBy optional leading sort field
     * @param descending sort direction
     * @param limit maximum rows per page
     * @param offset number of records to skip
     */
    public RecordQuery(List<Condition> conditions, String orderBy, boolean descending,
                       int limit, int offset) {
        this(conditions, orderBy, descending, limit, offset, null);
    }

    /** Supported portable comparison operations. */
    public enum Operator { EQ, NE, GT, GTE, LT, LTE }

    /** A typed filter with a single parameter; null is supported by EQ and NE only. */
    public record Condition(String field, Operator operator, Object value) {
        /**
         * Validates the supplied record components before construction.
         *
         * @param field validated field name or field definition
         * @param operator comparison operation to apply
         * @param value value to encode, validate, or store
         */
        public Condition {
            Identifiers.require(field);
            Objects.requireNonNull(operator, "operator");
            if (value == null && operator != Operator.EQ && operator != Operator.NE) {
                throw new IllegalArgumentException("Null is only supported for EQ/NE");
            }
        }
    }

    /**
     * Validates the supplied record components before construction.
     *
     * @param conditions value of conditions used by this operation
     * @param orderBy value of order by used by this operation
     * @param descending whether rows should be sorted in descending order
     * @param limit maximum number of aggregate results to return
     * @param offset number of records to skip before returning a page
     * @param afterPrimaryKey optional keyset cursor identifying the last consumed row
     */
    public RecordQuery {
        conditions = List.copyOf(conditions);
        if (orderBy != null) Identifiers.require(orderBy);
        if (limit < 1 || limit > 10_000) throw new IllegalArgumentException("limit must be between 1 and 10000");
        if (offset < 0) throw new IllegalArgumentException("offset cannot be negative");
        if (afterPrimaryKey != null && offset != 0) {
            throw new IllegalArgumentException("Keyset pagination cannot be combined with OFFSET");
        }
    }

    /** Starts a bounded query with a safe default result limit. */
    public static RecordQuery all() {
        return new RecordQuery(List.of(), null, false, 100, 0);
    }

    /** Adds a condition to the immutable AND predicate list. */
    public RecordQuery where(String field, Operator operator, Object value) {
        List<Condition> updated = new ArrayList<>(conditions);
        updated.add(new Condition(field, operator, value));
        return new RecordQuery(updated, orderBy, descending, limit, offset, afterPrimaryKey);
    }

    /** Returns an ordered query; the field is checked against the schema at execution. */
    public RecordQuery orderBy(String field, boolean descending) {
        return new RecordQuery(conditions, field, descending, limit, offset, afterPrimaryKey);
    }

    /** Sets a bounded page size and offset. */
    public RecordQuery page(int offset, int limit) {
        return new RecordQuery(conditions, orderBy, descending, limit, offset, afterPrimaryKey);
    }

    /**
     * Continues an ordered scan strictly after the given primary key.
     * The identifier must be a scalar for single keys or {@link CompositeKey} for compound keys.
     * The first page is obtained without a cursor; subsequent pages use the last row's identifier.
     *
     * @param lastKey last fully consumed primary key
     * @return an immutable query using keyset pagination without OFFSET
     */
    public RecordQuery afterPrimaryKey(Object lastKey) {
        return new RecordQuery(conditions, orderBy, descending, limit, 0,
                Objects.requireNonNull(lastKey, "lastKey"));
    }

    /** Ensures every field exists in the target collection schema. */
    public void validate(CollectionDefinition schema) {
        if (afterPrimaryKey != null) {
            schema.keyValues(afterPrimaryKey);
            if (orderBy != null && !orderBy.equals(schema.idField())) {
                throw new IllegalArgumentException("Keyset scanning requires primary-key ordering");
            }
            for (String key : schema.idFields()) {
                FieldType type = schema.field(key).type();
                if (type == FieldType.BOOLEAN || type == FieldType.BYTES
                        || type == FieldType.JSON || type == FieldType.LONG_TEXT) {
                    throw new IllegalArgumentException("Primary-key type does not support portable keyset ordering: " + type);
                }
            }
        }
        for (Condition condition : conditions) {
            CollectionDefinition.Field field = schema.field(condition.field());
            if (field.type() == FieldType.JSON) {
                throw new IllegalArgumentException("Portable filters cannot compare structured JSON fields: "
                        + condition.field());
            }
            schema.validateValue(field, condition.value());
        }
        if (orderBy != null && (schema.field(orderBy).type() == FieldType.JSON
                || schema.field(orderBy).type() == FieldType.LONG_TEXT)) {
            throw new IllegalArgumentException("Portable JSON fields cannot be ordered: " + orderBy);
        }
    }
}
