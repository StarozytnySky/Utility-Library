package org.broken.arrow.library.database.jdbc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * JSON storage for relational providers. Gson is also used by Serialize Utility;
 * database values are explicit portable trees rather than arbitrary class graphs.
 */
final class JsonValues {
    private static final Gson GSON = new Gson();
    private static final int MAX_DEPTH = 24;
    private static final int MAX_JSON_CHARS = 16 * 1024 * 1024;
    private static final int MAX_ELEMENTS = 50_000;

    private JsonValues() { }

    /** Serializes a validated structured value to a SQL text column. */
    static String toJson(Object value) {
        String json = GSON.toJson(value);
        if (json.length() > MAX_JSON_CHARS) {
            throw new IllegalArgumentException("Structured JSON exceeds maximum stored size");
        }
        return json;
    }

    /** Parses SQL JSON text without converting integral values through floating point. */
    static Object fromJson(String text) {
        if (text.length() > MAX_JSON_CHARS) {
            throw new IllegalArgumentException("Stored JSON exceeds maximum permitted size");
        }
        return decode(JsonParser.parseString(text), 0);
    }

    private static Object decode(JsonElement value, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("Stored JSON exceeds maximum nesting");
        if (value == null || value instanceof JsonNull) return null;
        if (value instanceof JsonObject object) {
            if (object.size() > MAX_ELEMENTS) throw new IllegalArgumentException("Stored JSON object too large");
            Map<String, Object> result = new LinkedHashMap<>();
            for (var entry : object.entrySet()) {
                result.put(entry.getKey(), decode(entry.getValue(), depth + 1));
            }
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof JsonArray array) {
            if (array.size() > MAX_ELEMENTS) throw new IllegalArgumentException("Stored JSON array too large");
            List<Object> result = new ArrayList<>(array.size());
            for (JsonElement item : array) result.add(decode(item, depth + 1));
            return Collections.unmodifiableList(result);
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) return primitive.getAsBoolean();
        if (primitive.isString()) return primitive.getAsString();
        if (primitive.isNumber()) {
            BigDecimal number = primitive.getAsBigDecimal();
            try {
                return number.intValueExact();
            } catch (ArithmeticException ignore) {
                try {
                    return number.longValueExact();
                } catch (ArithmeticException ignoreToo) {
                    return number;
                }
            }
        }
        throw new IllegalArgumentException("Unsupported structured JSON primitive");
    }
}
