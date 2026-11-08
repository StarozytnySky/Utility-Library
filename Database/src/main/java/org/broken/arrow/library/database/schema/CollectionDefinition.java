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
        /**
         * Validates the supplied record components before construction.
         *
         * @param name validated name of the resource
         * @param type declared Java or database field type
         * @param nullable whether this field can contain null
         */
        public Field {
            Identifiers.require(name);
            Objects.requireNonNull(type, "type");
        }
    }

    /** Portable secondary index declaration, including composite unique constraints. */
    public record Index(String name, List<String> columns, boolean unique) {
        /**
         * Validates the supplied record components before construction.
         *
         * @param name validated name of the resource
         * @param columns validated index columns in their declared order
         * @param unique whether duplicate combinations of indexed values are forbidden
         */
        public Index {
            Identifiers.require(name);
            if (name.length() > 63) {
                throw new IllegalArgumentException("Index name exceeds PostgreSQL's 63-byte identifier limit");
            }
            columns = List.copyOf(columns);
            if (columns.isEmpty()) throw new IllegalArgumentException("An index must include at least one column");
            if (columns.size() != new HashSet<>(columns).size()) {
                throw new IllegalArgumentException("Duplicate columns in database index: " + name);
            }
            columns.forEach(Identifiers::require);
        }
    }

    private final String name;
    private final String idField;
    private final List<Field> fields;
    private final List<Index> indexes;

    private CollectionDefinition(String name, String idField, List<Field> fields, List<Index> indexes) {
        this.name = name;
        this.idField = idField;
        this.fields = List.copyOf(fields);
        this.indexes = List.copyOf(indexes);
    }

    /**
     * Creates a builder where the primary-key field must be defined explicitly.
     *
     * @param name validated name of the resource
     * @param idField record component used as the primary key
     * @param idType value of id type used by this operation
     * @return builder for a validated table or collection definition
     */
    public static Builder named(String name, String idField, FieldType idType) {
        return new Builder(Identifiers.require(name), Identifiers.require(idField), idType);
    }

    /**
     * Returns the SQL table or MongoDB collection name.
     *
     * @return declared name of the field, index, or collection
     */
    public String name() { return name; }

    /**
     * Returns the logical primary-key field name.
     *
     * @return string representation of the requested value
     */
    public String idField() { return idField; }

    /**
     * Returns the immutable field declarations.
     *
     * @return collection of values matching the requested operation
     */
    public List<Field> fields() { return fields; }

    /**
     * Returns the secondary and composite indexes created during schema bootstrap.
     *
     * @return collection of values matching the requested operation
     */
    public List<Index> indexes() { return indexes; }

    /**
     * Returns the declared field type or rejects unknown fields.
     *
     * @param fieldName validated field name
     * @return matching field definition in the collection schema
     */
    public Field field(String fieldName) {
        for (Field field : fields) {
            if (field.name().equals(fieldName)) return field;
        }
        throw new IllegalArgumentException("Unknown database field: " + fieldName);
    }

    /**
     * Validates a record before a provider receives it, including value portability.
     *
     * @param values ordered values to bind or encode
     * @param requireAll whether every defined field must be present
     */
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

    /**
     * Checks that a filter/write parameter fits the declared backend-neutral type.
     *
     * @param field validated field name or field definition
     * @param value value to encode, validate, or store
     */
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
        // MySQL/MariaDB require a bounded VARCHAR for portable string indexes.
        if (field.type() == FieldType.STRING && value instanceof String text
                && indexes.stream().anyMatch(index -> index.columns().contains(field.name()))
                && text.length() > 191) {
            throw new IllegalArgumentException("Indexed string exceeds 191 characters: " + field.name());
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
        private final List<Index> indexes = new ArrayList<>();
        private final Set<String> indexNames = new HashSet<>();

        private Builder(String name, String idField, FieldType idType) {
            this.name = name;
            this.idField = idField;
            field(idField, idType, false);
        }

        /**
         * Adds one field, preventing duplicate column names.
         *
         * @param name validated name of the resource
         * @param type declared Java or database field type
         * @param nullable whether this field can contain null
         * @return matching field definition in the collection schema
         */
        public Builder field(String name, FieldType type, boolean nullable) {
            Field field = new Field(name, type, nullable);
            if (!names.add(name)) throw new IllegalArgumentException("Duplicate field: " + name);
            fields.add(field);
            return this;
        }

        /**
         * Defines a provider-neutral compound index; only scalar fields are supported.
         *
         * @param name validated name of the resource
         * @param unique whether duplicate combinations of indexed values are forbidden
         * @param columns validated index columns in their declared order
         * @return new schema builder containing the requested index
         */
        public Builder index(String name, boolean unique, String... columns) {
            Index index = new Index(name, List.of(columns), unique);
            if (!indexNames.add(index.name())) {
                throw new IllegalArgumentException("Duplicate index name: " + index.name());
            }
            indexes.add(index);
            return this;
        }

        /**
         * Validates index targets before a provider can create any schema objects.
         *
         * @return validated immutable result of the builder
         */
        public CollectionDefinition build() {
            CollectionDefinition snapshot = new CollectionDefinition(name, idField, fields, indexes);
            for (Index index : indexes) {
                for (String column : index.columns()) {
                    Field field = snapshot.field(column);
                    if (index.unique() && field.nullable()) {
                        throw new IllegalArgumentException("Portable unique indexes cannot contain nullable columns: " + column);
                    }
                    if (field.type() == FieldType.JSON || field.type() == FieldType.BYTES) {
                        throw new IllegalArgumentException("Structured and binary fields cannot be indexed portably: " + column);
                    }
                }
            }
            return snapshot;
        }
    }
}
