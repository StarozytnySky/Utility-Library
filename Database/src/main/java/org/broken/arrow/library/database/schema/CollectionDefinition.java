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
    private final List<String> idFields;
    private final List<Field> fields;
    private final List<Index> indexes;

    private CollectionDefinition(String name, List<String> idFields, List<Field> fields, List<Index> indexes) {
        this.name = name;
        this.idFields = List.copyOf(idFields);
        this.idField = idFields.getFirst();
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
        if (idType == FieldType.LONG_TEXT) {
            throw new IllegalArgumentException("Large text cannot be a primary key");
        }
        return new Builder(Identifiers.require(name), Identifiers.require(idField), idType);
    }

    /**
     * Creates a schema whose identity consists of multiple required scalar fields.
     * The first component remains the stable default sort field.
     *
     * @param name validated table or collection name
     * @param keys ordered mapping of primary-key column names to their scalar types
     * @return builder preconfigured with the composite primary key
     */
    public static Builder namedComposite(String name, java.util.Map<String, FieldType> keys) {
        if (keys.size() < 2) throw new IllegalArgumentException("Composite keys require two or more fields");
        var entries = new java.util.LinkedHashMap<>(keys);
        for (FieldType type : entries.values()) {
            if (type == FieldType.JSON || type == FieldType.BYTES || type == FieldType.LONG_TEXT) {
                throw new IllegalArgumentException("Composite keys require scalar bounded fields");
            }
        }
        var builder = new Builder(Identifiers.require(name), entries.keySet().iterator().next(),
                entries.values().iterator().next());
        for (var entry : entries.entrySet()) {
            if (!entry.getKey().equals(builder.idField)) builder.field(entry.getKey(), entry.getValue(), false);
        }
        builder.primaryKeys = List.copyOf(entries.keySet());
        return builder;
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
     * Returns the ordered columns that form the primary key.
     *
     * @return immutable list of primary-key field names
     */
    public List<String> idFields() { return idFields; }

    /**
     * Checks whether a field is part of the primary key.
     *
     * @param field field name to check
     * @return true if the field belongs to the primary key
     */
    public boolean isIdField(String field) { return idFields.contains(field); }

    /**
     * Extracts a primary key from the mapped record values.
     *
     * @param values map of column names to stored field values
     * @return scalar key for a single-column identifier, or a composite key otherwise
     */
    public Object idFrom(java.util.Map<String, ?> values) {
        if (idFields.size() == 1) return values.get(idField);
        return new CompositeKey(idFields.stream().<Object>map(values::get).toList());
    }

    /**
     * Validates a logical primary key and extracts its components in column order.
     *
     * @param id scalar key or {@link CompositeKey} to validate
     * @return ordered key values compatible with the declared field types
     */
    public List<Object> keyValues(Object id) {
        if (idFields.size() == 1) {
            validateValue(field(idField), Objects.requireNonNull(id, "id"));
            return List.of(id);
        }
        if (!(id instanceof CompositeKey key) || key.values().size() != idFields.size()) {
            throw new IllegalArgumentException("Expected a composite key with " + idFields.size() + " components");
        }
        for (int i = 0; i < idFields.size(); i++) {
            validateValue(field(idFields.get(i)), key.values().get(i));
        }
        return key.values();
    }

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
        for (String key : idFields) {
            if (!values.containsKey(key) || values.get(key) == null) {
                throw new IllegalArgumentException("Missing primary key: " + key);
            }
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
            case LONG_TEXT -> value instanceof String;
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
                && (isIdField(field.name()) || indexes.stream()
                .anyMatch(index -> index.columns().contains(field.name())))
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
        private List<String> primaryKeys;
        private final List<Field> fields = new ArrayList<>();
        private final Set<String> names = new HashSet<>();
        private final List<Index> indexes = new ArrayList<>();
        private final Set<String> indexNames = new HashSet<>();

        private Builder(String name, String idField, FieldType idType) {
            this.name = name;
            this.idField = idField;
            this.primaryKeys = List.of(idField);
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
            CollectionDefinition snapshot = new CollectionDefinition(name, primaryKeys, fields, indexes);
            for (Index index : indexes) {
                for (String column : index.columns()) {
                    Field field = snapshot.field(column);
                    if (field.type() == FieldType.JSON || field.type() == FieldType.BYTES
                            || field.type() == FieldType.LONG_TEXT) {
                        throw new IllegalArgumentException("Structured and binary fields cannot be indexed portably: " + column);
                    }
                }
            }
            return snapshot;
        }
    }
}
