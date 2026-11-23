package org.broken.arrow.library.database.mongo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.broken.arrow.library.database.schema.CollectionDefinition;
import org.broken.arrow.library.database.schema.FieldType;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.Decimal128;

/** Portable scalar codec: MongoDB stores the logical identifier in its unique _id key. */
final class MongoValues {
    private MongoValues() { }

    /**
     * Maps a declared collection field onto an engine-specific document field name.
     *
     * @param schema definition of the target table or collection
     * @param field validated field name or field definition
     * @return declared name of the field, index, or collection
     */
    static String name(CollectionDefinition schema, String field) {
        schema.field(field);
        return schema.idFields().size() == 1 && schema.idField().equals(field) ? "_id" : field;
    }

    /**
     * Converts a typed value into a BSON-compatible value without reflection.
     *
     * @param type declared Java or database field type
     * @param value value to encode, validate, or store
     * @return backend-compatible representation of the value
     */
    static Object encode(FieldType type, Object value) {
        if (value == null) return null;
        return switch (type) {
            case UUID -> value instanceof UUID id ? id.toString() : UUID.fromString(value.toString()).toString();
            case INSTANT -> value instanceof Instant timestamp ? timestamp.toEpochMilli() : ((Number) value).longValue();
            case DECIMAL -> new Decimal128((BigDecimal) value);
            case BYTES -> new Binary((byte[]) value);
            case JSON -> jsonValue(value);
            case INTEGER -> ((Number) value).intValue();
            case LONG -> ((Number) value).longValue();
            case DOUBLE -> ((Number) value).doubleValue();
            case BOOLEAN -> (Boolean) value;
            case STRING -> value instanceof Enum<?> item ? item.name() : (String) value;
            case LONG_TEXT -> (String) value;
        };
    }

    /**
     * Converts BSON into the exact declared Java domain type.
     *
     * @param type declared Java or database field type
     * @param value value to encode, validate, or store
     * @return decoded, strongly typed Java value
     */
    static Object decode(FieldType type, Object value) {
        if (value == null) return null;
        return switch (type) {
            case UUID -> UUID.fromString(value.toString());
            case INSTANT -> Instant.ofEpochMilli(((Number) value).longValue());
            case DECIMAL -> value instanceof Decimal128 decimal ? decimal.bigDecimalValue()
                    : new BigDecimal(value.toString());
            case BYTES -> value instanceof Binary binary ? binary.getData() : (byte[]) value;
            case JSON -> jsonValue(value);
            case INTEGER -> ((Number) value).intValue();
            case LONG -> ((Number) value).longValue();
            case DOUBLE -> ((Number) value).doubleValue();
            case BOOLEAN -> (Boolean) value;
            case STRING, LONG_TEXT -> value.toString();
        };
    }

    /**
     * Normalizes structured objects into plain BSON-compatible maps and lists.
     *
     * @param value value to encode, validate, or store
     * @return computed result of the requested operation
     */
    private static Object jsonValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Integer || value instanceof Long || value instanceof Double) return value;
        if (value instanceof Decimal128 decimal) return decimal.bigDecimalValue().toPlainString();
        if (value instanceof BigDecimal decimal) return decimal.toPlainString();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("Structured map key must be String");
                }
                normalized.put(key, jsonValue(entry.getValue()));
            }
            return normalized;
        }
        if (value instanceof java.util.List<?> list) {
            java.util.List<Object> normalized = new java.util.ArrayList<>(list.size());
            for (Object item : list) normalized.add(jsonValue(item));
            return normalized;
        }
        throw new IllegalArgumentException("Unsupported BSON JSON value: " + value.getClass().getName());
    }

    /**
     * Builds a complete MongoDB document from schema-validated logical values.
     *
     * @param schema definition of the target table or collection
     * @param values ordered values to bind or encode
     * @return backend-compatible representation of the value
     */
    static Document encode(CollectionDefinition schema, Map<String, Object> values) {
        schema.validateValues(values, true);
        Document document = new Document();
        for (CollectionDefinition.Field field : schema.fields()) {
            if (values.containsKey(field.name())) {
                document.put(name(schema, field.name()), encode(field.type(), values.get(field.name())));
            }
        }
        if (schema.idFields().size() > 1) {
            document.put("_id", compoundId(schema, schema.keyValues(schema.idFrom(values))));
        }
        return document;
    }

    /**
     * Converts a BSON document back into a nullable-aware immutable logical map.
     *
     * @param schema definition of the target table or collection
     * @param document MongoDB document to decode into typed fields
     * @return decoded, strongly typed Java value
     */
    static Map<String, Object> decode(CollectionDefinition schema, Document document) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (CollectionDefinition.Field field : schema.fields()) {
            values.put(field.name(), decode(field.type(), document.get(name(schema, field.name()))));
        }
        return Collections.unmodifiableMap(values);
    }

    /**
     * Encodes a primary key using the schema's declared type.
     *
     * @param schema definition of the target table or collection
     * @param id stable primary-key value
     * @return identifier of the supplied record
     */
    static Object id(CollectionDefinition schema, Object id) {
        if (id == null) throw new IllegalArgumentException("Identifier is required");
        if (schema.idFields().size() == 1) {
            return encode(schema.field(schema.idField()).type(), id);
        }
        return compoundId(schema, schema.keyValues(id));
    }

    /**
     * Produces one deterministic MongoDB identifier from an ordered compound key.
     *
     * @param schema declared collection metadata
     * @param values validated primary-key values
     * @return compound MongoDB identifier with stable component ordering
     */
    private static Document compoundId(CollectionDefinition schema, java.util.List<Object> values) {
        Document key = new Document();
        for (int index = 0; index < schema.idFields().size(); index++) {
            String field = schema.idFields().get(index);
            key.put(field, encode(schema.field(field).type(), values.get(index)));
        }
        return key;
    }
}
