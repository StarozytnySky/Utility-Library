package org.broken.arrow.library.database.codec;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.broken.arrow.library.database.schema.CollectionDefinition;
import org.broken.arrow.library.database.schema.FieldType;
import org.broken.arrow.library.database.schema.Identifiers;
import org.broken.arrow.library.database.schema.StoredField;
import org.broken.arrow.library.database.schema.StoredIndex;

/**
 * Compiles record schema metadata and typed value adapters once at registration.
 *
 * <p>Accessors use cached method handles; no reflection or dynamic annotations
 * are evaluated during reads/writes. Fields are stored using their configured
 * names and can contain supported nested records, lists, sets, maps and custom
 * value codecs.</p>
 */
public final class RecordCodecs {
    private RecordCodecs() { }

    /**
     * Derives the schema and mapping from a Java record using built-in value codecs.
     *
     * @param type Java record class
     * @param collection SQL table or MongoDB collection name
     * @param idField name of the Java record component used as its identifier
     * @return compiled reusable codec
     * @param <T> record type
     * @param <ID> identifier type
     */
    public static <T extends Record, ID> RecordCodec<T, ID> of(
            Class<T> type, String collection, String idField) {
        return of(type, collection, idField, ValueCodecs.defaults());
    }

    /**
     * Creates a typed record codec with a composite primary key and unchanged column names.
     *
     * @param type Java record class
     * @param collection SQL table or MongoDB collection name
     * @param idFields names of the record components forming the composite primary key
     * @return compiled reusable codec with a composite identifier
     * @param <T> record type
     */
    public static <T extends Record> RecordCodec<T, org.broken.arrow.library.database.schema.CompositeKey>
    ofComposite(Class<T> type, String collection, String... idFields) {
        if (idFields == null || idFields.length < 2) {
            throw new IllegalArgumentException("A composite key requires at least two components");
        }
        return build(type, collection, idFields, ValueCodecs.defaults());
    }

    /**
     * Derives a record codec with immutable, application-specific type mappings.
     *
     * @param type Java record class
     * @param collection table or collection name
     * @param idField identifier component name
     * @param valueCodecs installed custom mappings for complex Java values
     * @return compiled reusable record codec
     * @param <T> record type
     * @param <ID> identifier type
     */
    public static <T extends Record, ID> RecordCodec<T, ID> of(
            Class<T> type, String collection, String idField, ValueCodecs valueCodecs) {
        return build(type, collection, new String[]{idField}, valueCodecs);
    }

    private static <T extends Record, ID> RecordCodec<T, ID> build(
            Class<T> type, String collection, String[] idFields, ValueCodecs valueCodecs) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(valueCodecs, "valueCodecs");
        if (idFields.length == 0 || java.util.Set.of(idFields).size() != idFields.length) {
            throw new IllegalArgumentException("Invalid identifier components");
        }
        Identifiers.require(collection);
        for (String idField : idFields) Identifiers.require(idField);
        if (!type.isRecord()) throw new IllegalArgumentException("Only Java records can be auto-mapped");
        RecordComponent[] components = type.getRecordComponents();
        if (components.length == 0) throw new IllegalArgumentException("Record cannot be empty");
        java.util.List<Integer> idIndexes = new java.util.ArrayList<>();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        MethodHandle[] accessors = new MethodHandle[components.length];
        ValueCodecs.Adapter[] adapters = new ValueCodecs.Adapter[components.length];
        String[] columns = new String[components.length];
        boolean[] nullable = new boolean[components.length];
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(type, MethodHandles.lookup());
            for (int index = 0; index < components.length; index++) {
                RecordComponent component = components[index];
                StoredField metadata = component.getAnnotation(StoredField.class);
                parameterTypes[index] = component.getType();
                accessors[index] = lookup.unreflect(component.getAccessor());
                columns[index] = metadata != null && !metadata.name().isBlank()
                        ? Identifiers.require(metadata.name()) : Identifiers.require(component.getName());
                nullable[index] = metadata != null && metadata.nullable();
                if (nullable[index] && component.getType().isPrimitive()) {
                    throw new IllegalArgumentException("Primitive cannot be nullable: " + component.getName());
                }
                adapters[index] = valueCodecs.adapter(component.getGenericType(),
                        metadata == null ? ValueCodec.Auto.class : metadata.codec(), 0);
                if (metadata != null && metadata.longText()
                        && adapters[index].fieldType() != org.broken.arrow.library.database.schema.FieldType.STRING) {
                    throw new IllegalArgumentException("Large text requires a String field: " + component.getName());
                }
                if (java.util.Arrays.asList(idFields).contains(component.getName())) {
                    if (metadata != null && metadata.longText()) {
                        throw new IllegalArgumentException("Large text cannot be a primary key");
                    }
                    idIndexes.add(index);
                    if (nullable[index]) throw new IllegalArgumentException("Identifier must not be nullable");
                    if (adapters[index].fieldType() == FieldType.JSON) {
                        throw new IllegalArgumentException("Structured values cannot be primary keys");
                    }
                }
            }
            if (idIndexes.size() != idFields.length) throw new IllegalArgumentException("Missing identifier components");
            // Preserve the caller-declared key order instead of record component order.
            java.util.LinkedHashMap<String, FieldType> ordered = new java.util.LinkedHashMap<>();
            java.util.List<Integer> orderedIndexes = new java.util.ArrayList<>();
            for (String key : idFields) {
                for (int i = 0; i < components.length; i++) {
                    if (components[i].getName().equals(key)) {
                        ordered.put(columns[i], adapters[i].fieldType());
                        orderedIndexes.add(i);
                    }
                }
            }
            CollectionDefinition.Builder schema = idFields.length == 1
                    ? CollectionDefinition.named(collection, ordered.keySet().iterator().next(),
                        ordered.values().iterator().next())
                    : CollectionDefinition.namedComposite(collection, ordered);
            for (int index = 0; index < components.length; index++) {
                if (!orderedIndexes.contains(index)) {
                    StoredField metadata = components[index].getAnnotation(StoredField.class);
                    schema.field(columns[index], metadata != null && metadata.longText()
                            ? org.broken.arrow.library.database.schema.FieldType.LONG_TEXT
                            : adapters[index].fieldType(), nullable[index]);
                }
            }
            for (StoredIndex index : type.getAnnotationsByType(StoredIndex.class)) {
                schema.index(index.name(), index.unique(), index.columns());
            }
            Constructor<T> constructor = type.getDeclaredConstructor(parameterTypes);
            MethodHandle creator = lookup.unreflectConstructor(constructor)
                    .asSpreader(Object[].class, components.length);
            return new GeneratedCodec<>(schema.build(), columns, nullable, adapters,
                    accessors, creator, orderedIndexes.stream().mapToInt(Integer::intValue).toArray());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("Record must expose an accessible canonical constructor: "
                    + type.getName(), failure);
        }
    }

    private record GeneratedCodec<T extends Record, ID>(
            CollectionDefinition schema, String[] fields, boolean[] nullable,
            ValueCodecs.Adapter[] adapters, MethodHandle[] getters,
            MethodHandle constructor, int[] idIndexes) implements RecordCodec<T, ID> {

        /** Returns immutable schema metadata. */
        @Override public CollectionDefinition schema() { return schema; }

        /** Extracts the identifier through the cached method handle. */
        @Override @SuppressWarnings("unchecked")
        public ID id(T model) {
            Objects.requireNonNull(model, "model");
            try {
                if (idIndexes.length == 1) return (ID) getters[idIndexes[0]].invoke(model);
                Object[] values = new Object[idIndexes.length];
                for (int i = 0; i < idIndexes.length; i++) values[i] = getters[idIndexes[i]].invoke(model);
                return (ID) org.broken.arrow.library.database.schema.CompositeKey.of(values);
            } catch (Throwable failure) {
                throw new IllegalStateException("Cannot read database identifier", failure);
            }
        }

        /** Encodes all fields with the adapters prepared at codec registration time. */
        @Override
        public Map<String, Object> encode(T model) {
            Objects.requireNonNull(model, "model");
            Map<String, Object> values = new LinkedHashMap<>();
            for (int index = 0; index < fields.length; index++) {
                Object value;
                try {
                    value = getters[index].invoke(model);
                } catch (Throwable failure) {
                    throw new IllegalStateException("Cannot read database field: " + fields[index], failure);
                }
                if (value == null && !nullable[index]) {
                    throw new IllegalArgumentException("Non-nullable field is null: " + fields[index]);
                }
                values.put(fields[index], value == null ? null : adapters[index].encode(value));
            }
            schema.validateValues(values, true);
            return Collections.unmodifiableMap(values);
        }

        /** Decodes required and nullable fields into the canonical record constructor. */
        @Override @SuppressWarnings("unchecked")
        public T decode(Map<String, Object> values) {
            Objects.requireNonNull(values, "values");
            Object[] parameters = new Object[fields.length];
            for (int index = 0; index < fields.length; index++) {
                if (!values.containsKey(fields[index])) {
                    throw new IllegalArgumentException("Missing persisted record field: " + fields[index]);
                }
                Object value = values.get(fields[index]);
                if (value == null && !nullable[index]) {
                    throw new IllegalArgumentException("Required persisted record field is null: " + fields[index]);
                }
                parameters[index] = value == null ? null : adapters[index].decode(value);
            }
            try {
                return (T) constructor.invoke(parameters);
            } catch (Throwable failure) {
                throw new IllegalStateException("Cannot reconstruct persisted record", failure);
            }
        }
    }
}
