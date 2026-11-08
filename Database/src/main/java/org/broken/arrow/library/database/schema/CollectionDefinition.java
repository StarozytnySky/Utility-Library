package org.broken.arrow.library.database.schema;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.broken.arrow.library.database.codec.ValueCodecs;

/** Immutable, portable collection metadata shared by schema and CRUD operations. */
public final class CollectionDefinition {
    /** Portable field declaration. */
    public record Field(String name, FieldType type, boolean nullable) {
        public Field {
            Identifiers.require(name);
            Objects.requireNonNull(type, "type");
        }
    }

    private final String name;
    private final String idField;
    private final List<Field> fields;

    private CollectionDefinition(String name, String idField, List<Field> fields) {
        this.name = name;
        this.idField = idField;
        this.fields = List.copyOf(fields);
    }

    /** Creates a builder where the primary-key field must be defined explicitly. */
    public static Builder named(String name, String idField, FieldType idType) {
        return new Builder(Identifiers.require(name), Identifiers.require(idField), idType);
    }

    /** Returns the SQL table or MongoDB collection name. */
    public String name() { return name; }

    /** Returns the logical primary-key field name. */
    public String idField() { return idField; }

    /** Returns the immutable field declarations. */
    public List<Field> fields() { return fields; }

    /** Returns the declared field type or rejects unknown fields. */
    public Field field(String fieldName) {
        for (Field field : fields) {
            if (field.name().equals(fieldName)) return field;
        }
        throw new IllegalArgumentException("Unknown database field: " + fieldName);
    }

    /** Validates a record before a provider receives it, including value portability. */
    public void validateValues(java.util.Map<String, ?> values, boolean requireAll) {
        Objects.requireNonNull(values, "values");
        for (var entry : values.entrySet()) {
            Field field = field(entry.getKey());
            validateValue(field, entry.getValue());
        }
        if (!values.containsKey(idField) || values.get(idField) == null) {
            throw new IllegalArgumentException("Missing primary key: " + idField);
        }
        if (requireAll) {
            for (Field field : fields) {
                if (!field.nullable() && values.get(field.name()) == null) {
                    throw new IllegalArgumentException("Missing required field: " + field.name());
                }
            }
        }
    }

    /** Checks that a filter/write parameter fits the declared backend-neutral type. */
    public void validateValue(Field field, Object value) {
        Objects.requireNonNull(field, "field");
        if (value == null) {
            if (!field.nullable()) throw new IllegalArgumentException("Non-nullable database field: " + field.name());
            return;
        }
        boolean valid = switch (field.type()) {
            case UUID -> value instanceof java.util.UUID;
            case INSTANT -> value instanceof java.time.Instant;
            case STRING -> value instanceof String || value instanceof Enum<?>;
            case INTEGER -> value instanceof Integer;
            case LONG -> value instanceof Long;
            case BOOLEAN -> value instanceof Boolean;
            case DOUBLE -> value instanceof Double number && Double.isFinite(number);
            case BYTES -> value instanceof byte[];
            case JSON -> {
                ValueCodecs.validatePortableValue(value);
                yield true;
            }
            case DECIMAL -> value instanceof java.math.BigDecimal number
                    && fitsDecimal(number);
        };
        if (!valid) {
            throw new IllegalArgumentException("Invalid or out-of-range value for database field: " + field.name());
        }
    }

    private static boolean fitsDecimal(java.math.BigDecimal value) {
        try {
            return value.setScale(10, java.math.RoundingMode.UNNECESSARY).precision() <= 38;
        } catch (ArithmeticException failure) {
            return false;
        }
    }

    /** Mutable one-time schema builder, not shared across threads. */
    public static final class Builder {
        private final String name;
        private final String idField;
        private final List<Field> fields = new ArrayList<>();
        private final Set<String> names = new HashSet<>();

        private Builder(String name, String idField, FieldType idType) {
            this.name = name;
            this.idField = idField;
            field(idField, idType, false);
        }

        /** Adds one field, preventing duplicate column names. */
        public Builder field(String name, FieldType type, boolean nullable) {
            Field field = new Field(name, type, nullable);
            if (!names.add(name)) throw new IllegalArgumentException("Duplicate field: " + name);
            fields.add(field);
            return this;
        }

        /** Returns an immutable schema snapshot. */
        public CollectionDefinition build() {
            return new CollectionDefinition(name, idField, fields);
        }
    }
}
