package org.broken.arrow.library.database.jdbc;

import java.math.BigDecimal;
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.broken.arrow.library.database.schema.CollectionDefinition;
import org.broken.arrow.library.database.schema.FieldType;

/** Shared scalar conversions for all JDBC providers; no reflection-based runtime mapping. */
final class JdbcValues {
    private JdbcValues() { }

    /**
     * Binds typed values including NULL in a stable order.
     *
     * @param statement prepared JDBC statement receiving bound parameters
     * @param values ordered values to bind or encode
     * @param types field types corresponding to bound parameters
     */
    static void bind(PreparedStatement statement, Object[] values, FieldType[] types) throws SQLException {
        if (values.length != types.length) throw new IllegalArgumentException("Parameter metadata mismatch");
        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            FieldType type = types[i];
            int index = i + 1;
            if (value == null) {
                statement.setNull(index, jdbcType(type).getVendorTypeNumber());
            } else {
                switch (type) {
                    case UUID -> statement.setString(index, value instanceof UUID ? value.toString() : value.toString());
                    case INSTANT -> statement.setLong(index, value instanceof Instant instant ? instant.toEpochMilli()
                            : ((Number) value).longValue());
                    case BOOLEAN -> statement.setBoolean(index, (Boolean) value);
                    case DECIMAL -> statement.setBigDecimal(index, (BigDecimal) value);
                    case BYTES -> statement.setBytes(index, (byte[]) value);
                    case JSON -> statement.setString(index, JsonValues.toJson(value));
                    case INTEGER -> statement.setInt(index, ((Number) value).intValue());
                    case LONG -> statement.setLong(index, ((Number) value).longValue());
                    case DOUBLE -> statement.setDouble(index, ((Number) value).doubleValue());
                    case STRING -> statement.setString(index, value instanceof Enum<?> item ? item.name() : (String) value);
                }
            }
        }
    }

    /**
     * Converts each declared column to the same logical value type across providers.
     *
     * @param results JDBC result set positioned before its next row
     * @param schema definition of the target table or collection
     * @return value produced by the database read callback
     */
    static Map<String, Object> read(ResultSet results, CollectionDefinition schema) throws SQLException {
        Map<String, Object> values = new LinkedHashMap<>();
        for (CollectionDefinition.Field field : schema.fields()) {
            Object value = field.type() == FieldType.JSON
                    ? results.getString(field.name()) : results.getObject(field.name());
            if (value != null) {
                value = switch (field.type()) {
                    case UUID -> UUID.fromString(value.toString());
                    case INSTANT -> Instant.ofEpochMilli(((Number) value).longValue());
                    case BOOLEAN -> value instanceof Boolean bool ? bool
                            : value instanceof Number n ? n.intValue() != 0 : Boolean.parseBoolean(value.toString());
                    case INTEGER -> ((Number) value).intValue();
                    case LONG -> ((Number) value).longValue();
                    case DOUBLE -> ((Number) value).doubleValue();
                    case DECIMAL -> value instanceof BigDecimal number ? number : new BigDecimal(value.toString());
                    case STRING -> value.toString();
                    case BYTES -> value;
                    case JSON -> JsonValues.fromJson((String) value);
                };
            }
            values.put(field.name(), value);
        }
        // Map.copyOf rejects nullable fields, so preserve an immutable nullable-aware map instead.
        return java.util.Collections.unmodifiableMap(values);
    }

    /**
     * Maps declared logical types to JDBC NULL type codes.
     *
     * @param type declared Java or database field type
     * @return computed result of the requested operation
     */
    static JDBCType jdbcType(FieldType type) {
        return switch (type) {
            case UUID, STRING -> JDBCType.VARCHAR;
            case INSTANT, LONG -> JDBCType.BIGINT;
            case INTEGER -> JDBCType.INTEGER;
            case BOOLEAN -> JDBCType.BOOLEAN;
            case DOUBLE -> JDBCType.DOUBLE;
            case DECIMAL -> JDBCType.DECIMAL;
            case BYTES -> JDBCType.BLOB;
            case JSON -> JDBCType.LONGVARCHAR;
        };
    }
}
