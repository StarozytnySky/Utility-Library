package org.broken.arrow.library.database.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.broken.arrow.library.database.schema.CollectionDefinition;
import org.broken.arrow.library.database.schema.FieldType;
import org.broken.arrow.library.database.schema.Identifiers;

/** Immutable AND query usable with SQL prepared statements and MongoDB BSON filters. */
public record RecordQuery(List<Condition> conditions, String orderBy, boolean descending,
                          int limit, int offset) {
    /** Supported portable comparison operations. */
    public enum Operator { EQ, NE, GT, GTE, LT, LTE }

    /** A typed filter with a single parameter; null is supported by EQ and NE only. */
    public record Condition(String field, Operator operator, Object value) {
        public Condition {
            Identifiers.require(field);
            Objects.requireNonNull(operator, "operator");
            if (value == null && operator != Operator.EQ && operator != Operator.NE) {
                throw new IllegalArgumentException("Null is only supported for EQ/NE");
            }
        }
    }

    public RecordQuery {
        conditions = List.copyOf(conditions);
        if (orderBy != null) Identifiers.require(orderBy);
        if (limit < 1 || limit > 10_000) throw new IllegalArgumentException("limit must be between 1 and 10000");
        if (offset < 0) throw new IllegalArgumentException("offset cannot be negative");
    }

    /** Starts a bounded query with a safe default result limit. */
    public static RecordQuery all() {
        return new RecordQuery(List.of(), null, false, 100, 0);
    }

    /** Adds a condition to the immutable AND predicate list. */
    public RecordQuery where(String field, Operator operator, Object value) {
        List<Condition> updated = new ArrayList<>(conditions);
        updated.add(new Condition(field, operator, value));
        return new RecordQuery(updated, orderBy, descending, limit, offset);
    }

    /** Returns an ordered query; the field is checked against the schema at execution. */
    public RecordQuery orderBy(String field, boolean descending) {
        return new RecordQuery(conditions, field, descending, limit, offset);
    }

    /** Sets a bounded page size and offset. */
    public RecordQuery page(int offset, int limit) {
        return new RecordQuery(conditions, orderBy, descending, limit, offset);
    }

    /** Ensures every field exists in the target collection schema. */
    public void validate(CollectionDefinition schema) {
        for (Condition condition : conditions) {
            CollectionDefinition.Field field = schema.field(condition.field());
            if (field.type() == FieldType.JSON) {
                throw new IllegalArgumentException("Portable filters cannot compare structured JSON fields: "
                        + condition.field());
            }
            schema.validateValue(field, condition.value());
        }
        if (orderBy != null && schema.field(orderBy).type() == FieldType.JSON) {
            throw new IllegalArgumentException("Portable JSON fields cannot be ordered: " + orderBy);
        }
    }
}
