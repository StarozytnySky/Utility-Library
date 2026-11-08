package org.broken.arrow.library.database.codec;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.broken.arrow.library.database.schema.FieldType;
import org.broken.arrow.library.database.schema.StoredField;

import org.broken.arrow.library.serialize.utility.serialize.ConfigurationSerializable;

/**
 * Immutable registry of custom value codecs plus type-safe built-in mapping.
 *
 * <p>Immutable registries make codec creation deterministic: adding a mapping
 * never changes codecs already in use. For collections, the element types are
 * resolved once from their generic record signatures. Nested records are
 * processed by cached method handles without reflection on the hot path.</p>
 */
public final class ValueCodecs {
    private static final int MAX_DEPTH = 24;
    private static final int MAX_ELEMENTS = 50_000;

    private final Map<Class<?>, ValueCodec<?>> custom;

    private ValueCodecs(Map<Class<?>, ValueCodec<?>> custom) {
        this.custom = Map.copyOf(custom);
    }

    /** Creates a registry containing the built-in scalar and structured codecs. */
    public static ValueCodecs defaults() {
        return new ValueCodecs(Map.of());
    }

    /**
     * Adds a codec for a custom type, returning a new immutable registry.
     * The custom codec must produce a JSON-compatible storage tree.
     *
     * @param type Java type to support
     * @param codec custom encoder/decoder
     * @return independent registry with the mapping installed
     * @param <T> Java value type
     */
    public <T> ValueCodecs with(Class<T> type, ValueCodec<T> codec) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(codec, "codec");
        if (type.isPrimitive()) throw new IllegalArgumentException("Custom codec type must be a reference type");
        Map<Class<?>, ValueCodec<?>> updated = new LinkedHashMap<>(custom);
        if (updated.putIfAbsent(type, codec) != null) {
            throw new IllegalArgumentException("A value codec is already registered for " + type.getName());
        }
        return new ValueCodecs(updated);
    }

    /**
     * Adapts an existing Serialize Utility model to portable database storage.
     *
     * <p>The explicit factory is preferred over invoking a static deserialize
     * method by reflection. It also keeps the database module independent of
     * Bukkit configuration initialization and avoids side effects while
     * decoding records on a worker thread.</p>
     *
     * @param restore application-specific deserialization function
     * @return an explicit codec for ConfigurationSerializable values
     * @param <T> serializable model type
     */
    public static <T extends ConfigurationSerializable> ValueCodec<T> configurationSerializable(
            Function<Map<String, Object>, T> restore) {
        Objects.requireNonNull(restore, "restore");
        return new ValueCodec<>() {
            /** Encodes with the existing Serialize Utility interface. */
            @Override
            public Object encode(T value) {
                return value.serialize();
            }

            /** Restores through the caller-provided, typed factory. */
            @Override
            @SuppressWarnings("unchecked")
            public T decode(Object stored) {
                if (!(stored instanceof Map<?, ?> entries)) {
                    throw new IllegalArgumentException("Expected a stored ConfigurationSerializable map");
                }
                return Objects.requireNonNull(restore.apply((Map<String, Object>) entries),
                        "ConfigurationSerializable restore must not return null");
            }
        };
    }

    /** A precompiled mapping used inside a record plan. */
    interface Adapter {
        FieldType fieldType();

        Object encode(Object value);

        Object decode(Object stored);
    }

    /** Resolves a declared component type and optional per-field codec. */
    Adapter adapter(Type type, Class<? extends ValueCodec<?>> override, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("Database model nesting exceeds " + MAX_DEPTH);
        Class<?> raw = rawClass(type);
        ValueCodec<?> specified = override == ValueCodec.Auto.class ? custom.get(raw) : instantiate(override);
        if (specified != null) return customAdapter(specified);

        if (raw == String.class || raw == UUID.class || raw == Instant.class || raw.isEnum()
                || raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class
                || raw == boolean.class || raw == Boolean.class || raw == double.class
                || raw == Double.class || raw == BigDecimal.class || raw == byte[].class) {
            Adapter primitive = scalar(raw);
            if (depth == 0) return primitive;
            return structured(value -> {
                Object encoded = primitive.encode(value);
                if (raw == UUID.class) return encoded.toString();
                if (raw == Instant.class) return ((Instant) encoded).toEpochMilli();
                if (raw == byte[].class) return java.util.Base64.getEncoder().encodeToString((byte[]) encoded);
                if (raw == BigDecimal.class) return ((BigDecimal) encoded).toPlainString();
                return encoded;
            }, stored -> {
                if (raw == byte[].class) {
                    if (!(stored instanceof String encoded)) throw new IllegalArgumentException("Expected Base64");
                    return primitive.decode(java.util.Base64.getDecoder().decode(encoded));
                }
                return primitive.decode(stored);
            });
        }
        if (raw == List.class || raw == Set.class || raw == Map.class) {
            if (!(type instanceof ParameterizedType parameterized)) {
                throw new IllegalArgumentException("Declare generic element types for " + type.getTypeName());
            }
            Type[] arguments = parameterized.getActualTypeArguments();
            if (raw == Map.class) {
                if (arguments.length != 2) {
                    throw new IllegalArgumentException("Portable maps require declared key and value types");
                }
                Class<?> keyType = rawClass(arguments[0]);
                if (keyType != String.class && keyType != UUID.class && !keyType.isEnum()) {
                    throw new IllegalArgumentException("Portable maps require String, UUID or enum keys: "
                            + type.getTypeName());
                }
                Adapter element = adapter(arguments[1], ValueCodec.Auto.class, depth + 1);
                return structured(value -> {
                    if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("Expected Map");
                    checkSize(map.size());
                    Map<String, Object> encoded = new LinkedHashMap<>();
                    for (var entry : map.entrySet()) {
                        String key = encodeKey(nonNull(entry.getKey()), keyType);
                        checkKey(key);
                        if (encoded.putIfAbsent(key, element.encode(nonNull(entry.getValue()))) != null) {
                            throw new IllegalArgumentException("Duplicate encoded map key: " + key);
                        }
                    }
                    return encoded;
                }, value -> {
                    if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("Expected stored map");
                    checkSize(map.size());
                    Map<Object, Object> decoded = new LinkedHashMap<>();
                    for (var entry : map.entrySet()) {
                        if (!(entry.getKey() instanceof String key)) {
                            throw new IllegalArgumentException("Stored map keys must be strings");
                        }
                        checkKey(key);
                        Object javaKey = decodeKey(key, keyType);
                        if (decoded.putIfAbsent(javaKey, element.decode(nonNull(entry.getValue()))) != null) {
                            throw new IllegalArgumentException("Duplicate decoded map key: " + key);
                        }
                    }
                    return Collections.unmodifiableMap(decoded);
                });
            }
            if (arguments.length != 1) throw new IllegalArgumentException("Collection requires one element type");
            Adapter element = adapter(arguments[0], ValueCodec.Auto.class, depth + 1);
            return structured(value -> {
                if (!(value instanceof java.util.Collection<?> elements)) {
                    throw new IllegalArgumentException("Expected Java Collection");
                }
                checkSize(elements.size());
                List<Object> encoded = new ArrayList<>(elements.size());
                for (Object item : elements) encoded.add(element.encode(nonNull(item)));
                return List.copyOf(encoded);
            }, value -> {
                if (!(value instanceof List<?> elements)) throw new IllegalArgumentException("Expected stored list");
                checkSize(elements.size());
                if (raw == Set.class) {
                    Set<Object> decoded = new LinkedHashSet<>();
                    for (Object item : elements) {
                        if (!decoded.add(element.decode(nonNull(item)))) {
                            throw new IllegalArgumentException("Duplicate set element in stored document");
                        }
                    }
                    return Collections.unmodifiableSet(decoded);
                }
                List<Object> decoded = new ArrayList<>(elements.size());
                for (Object item : elements) decoded.add(element.decode(nonNull(item)));
                return List.copyOf(decoded);
            });
        }
        if (raw.isRecord()) return recordAdapter(raw, depth + 1);
        throw new IllegalArgumentException("Unsupported database field type: " + type.getTypeName()
                + ". Register a ValueCodec for this type.");
    }

    private Adapter recordAdapter(Class<?> raw, int depth) {
        RecordComponent[] components = raw.getRecordComponents();
        MethodHandle[] accessors = new MethodHandle[components.length];
        String[] names = new String[components.length];
        Adapter[] adapters = new Adapter[components.length];
        boolean[] nullable = new boolean[components.length];
        Class<?>[] types = new Class<?>[components.length];
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(raw, MethodHandles.lookup());
            for (int index = 0; index < components.length; index++) {
                RecordComponent component = components[index];
                StoredField field = component.getAnnotation(StoredField.class);
                names[index] = field == null || field.name().isBlank() ? component.getName() : field.name();
                checkKey(names[index]);
                for (int previous = 0; previous < index; previous++) {
                    if (names[previous].equals(names[index])) {
                        throw new IllegalArgumentException("Duplicate nested record field: " + names[index]);
                    }
                }
                nullable[index] = field != null && field.nullable();
                if (nullable[index] && component.getType().isPrimitive()) {
                    throw new IllegalArgumentException("Primitive cannot be nullable: " + component.getName());
                }
                adapters[index] = adapter(component.getGenericType(),
                        field == null ? ValueCodec.Auto.class : field.codec(), depth + 1);
                accessors[index] = lookup.unreflect(component.getAccessor());
                types[index] = component.getType();
            }
            Constructor<?> canonical = raw.getDeclaredConstructor(types);
            MethodHandle constructor = lookup.unreflectConstructor(canonical).asSpreader(Object[].class, types.length);
            return structured(value -> {
                Map<String, Object> encoded = new LinkedHashMap<>();
                for (int index = 0; index < components.length; index++) {
                    Object fieldValue = invoke(accessors[index], value);
                    checkNullable(names[index], nullable[index], fieldValue);
                    encoded.put(names[index], fieldValue == null ? null : adapters[index].encode(fieldValue));
                }
                return encoded;
            }, stored -> {
                if (!(stored instanceof Map<?, ?> fields)) throw new IllegalArgumentException("Expected nested record map");
                if (fields.size() != components.length) {
                    throw new IllegalArgumentException("Unexpected nested record fields for " + raw.getName());
                }
                Object[] arguments = new Object[components.length];
                for (int index = 0; index < components.length; index++) {
                    if (!fields.containsKey(names[index])) {
                        throw new IllegalArgumentException("Missing nested field: " + names[index]);
                    }
                    Object value = fields.get(names[index]);
                    checkNullable(names[index], nullable[index], value);
                    arguments[index] = value == null ? null : adapters[index].decode(value);
                }
                return invoke(constructor, (Object) arguments);
            });
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("Cannot access nested record: " + raw.getName(), failure);
        }
    }

    private static Adapter scalar(Class<?> type) {
        FieldType kind;
        if (type == UUID.class) kind = FieldType.UUID;
        else if (type == Instant.class) kind = FieldType.INSTANT;
        else if (type == int.class || type == Integer.class) kind = FieldType.INTEGER;
        else if (type == long.class || type == Long.class) kind = FieldType.LONG;
        else if (type == boolean.class || type == Boolean.class) kind = FieldType.BOOLEAN;
        else if (type == double.class || type == Double.class) kind = FieldType.DOUBLE;
        else if (type == BigDecimal.class) kind = FieldType.DECIMAL;
        else if (type == byte[].class) kind = FieldType.BYTES;
        else kind = FieldType.STRING;
        return new Adapter() {
            /** Returns the portable SQL/BSON type. */
            @Override public FieldType fieldType() { return kind; }

            /** Encodes enum names; all other standalone scalars already use native portable types. */
            @Override public Object encode(Object value) {
                return value instanceof Enum<?> enumeration ? enumeration.name() : value;
            }

            /** Restores precise Java scalar types across JDBC and BSON. */
            @Override @SuppressWarnings({"unchecked", "rawtypes"})
            public Object decode(Object value) {
                if (type.isEnum()) {
                    if (!(value instanceof String string)) throw new IllegalArgumentException("Expected enum name");
                    return Enum.valueOf((Class<? extends Enum>) type, string);
                }
                if (type == UUID.class) return value instanceof UUID ? value : UUID.fromString(value.toString());
                if (type == Instant.class) return value instanceof Instant ? value
                        : Instant.ofEpochMilli(exactLong(value));
                if (type == int.class || type == Integer.class) return Math.toIntExact(exactLong(value));
                if (type == long.class || type == Long.class) return exactLong(value);
                if (type == BigDecimal.class) return value instanceof BigDecimal number
                        ? number : new BigDecimal(value.toString());
                if (type == double.class || type == Double.class) {
                    double number = value instanceof Number n ? n.doubleValue()
                            : Double.parseDouble(value.toString());
                    if (!Double.isFinite(number)) throw new IllegalArgumentException("Non-finite stored number");
                    return number;
                }
                if (type == boolean.class || type == Boolean.class) {
                    if (!(value instanceof Boolean)) throw new IllegalArgumentException("Expected boolean");
                    return value;
                }
                if (type == String.class && !(value instanceof String)) {
                    throw new IllegalArgumentException("Expected string");
                }
                if (type == byte[].class && !(value instanceof byte[])) {
                    throw new IllegalArgumentException("Expected binary field");
                }
                return value;
            }
        };
    }

    private static Adapter structured(Function<Object, Object> encode, Function<Object, Object> decode) {
        return new Adapter() {
            /** Complex values are stored as portable JSON/BSON structures. */
            @Override public FieldType fieldType() { return FieldType.JSON; }

            /** Converts and validates a Java structure. */
            @Override public Object encode(Object value) { return portable(encode.apply(value), 0); }

            /** Converts a stored structure into a declared Java type. */
            @Override public Object decode(Object stored) { return decode.apply(portable(stored, 0)); }
        };
    }

    @SuppressWarnings("unchecked")
    private static Adapter customAdapter(ValueCodec<?> codec) {
        ValueCodec<Object> typed = (ValueCodec<Object>) codec;
        return structured(typed::encode, typed::decode);
    }

    /** Ensures a supplied custom codec cannot introduce unexpected runtime types. */
    /**
     * Validates and normalizes a structured value for provider-independent storage.
     *
     * @param value structured Java value to validate
     * @return normalized, JSON-compatible representation
     */
    public static Object validatePortableValue(Object value) {
        return portable(value, 0);
    }

    static Object portable(Object value, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("Stored structure exceeds maximum nesting");
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Integer || value instanceof Long) return value;
        if (value instanceof BigDecimal number) return number.toPlainString();
        if (value instanceof Double number && Double.isFinite(number)) return number;
        if (value instanceof Float number && Float.isFinite(number)) return number.doubleValue();
        if (value instanceof Map<?, ?> map) {
            checkSize(map.size());
            Map<String, Object> encoded = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Map key must be String");
                checkKey(key);
                encoded.put(key, portable(entry.getValue(), depth + 1));
            }
            return Collections.unmodifiableMap(encoded);
        }
        if (value instanceof List<?> list) {
            checkSize(list.size());
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) result.add(portable(item, depth + 1));
            return Collections.unmodifiableList(result);
        }
        throw new IllegalArgumentException("Non-portable JSON value: " + value.getClass().getName());
    }

    /** Encodes supported typed map keys using reversible textual representations. */
    private static String encodeKey(Object value, Class<?> type) {
        if (type == String.class && value instanceof String string) return string;
        if (type == UUID.class && value instanceof UUID id) return id.toString();
        if (type.isEnum() && type.isInstance(value)) return ((Enum<?>) value).name();
        throw new IllegalArgumentException("Unexpected map key type: " + value.getClass().getName());
    }

    /** Restores keys after JSON/BSON object-key normalization. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object decodeKey(String value, Class<?> type) {
        if (type == String.class) return value;
        if (type == UUID.class) return UUID.fromString(value);
        if (type.isEnum()) return Enum.valueOf((Class<? extends Enum>) type, value);
        throw new IllegalArgumentException("Unsupported map key type: " + type.getName());
    }

    private static void checkSize(int size) {
        if (size > MAX_ELEMENTS) throw new IllegalArgumentException("Too many structured database elements");
    }

    private static void checkKey(String key) {
        if (key.isBlank() || key.contains(".") || key.startsWith("$") || key.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Non-portable MongoDB/JSON object key: " + key);
        }
    }

    private static Object nonNull(Object value) {
        return Objects.requireNonNull(value, "Collection elements and map values must not be null");
    }

    private static void checkNullable(String field, boolean nullable, Object value) {
        if (!nullable && value == null) throw new IllegalArgumentException("Required database field is null: " + field);
    }

    private static long exactLong(Object value) {
        try {
            return new BigDecimal(value.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException failure) {
            throw new IllegalArgumentException("Out-of-range stored integer: " + value, failure);
        }
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> raw) return raw;
        if (type instanceof ParameterizedType parameterized
                && parameterized.getRawType() instanceof Class<?> raw) return raw;
        throw new IllegalArgumentException("Unsupported generic/wildcard database type: " + type.getTypeName());
    }

    private static ValueCodec<?> instantiate(Class<? extends ValueCodec<?>> codec) {
        try {
            return Objects.requireNonNull(codec.getDeclaredConstructor().newInstance(), "codec instance");
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("Field codec requires an accessible no-argument constructor: "
                    + codec.getName(), failure);
        }
    }

    private static Object invoke(MethodHandle handle, Object parameter) {
        try {
            return handle.invoke(parameter);
        } catch (Throwable failure) {
            throw new IllegalArgumentException("Database record accessor failed", failure);
        }
    }
}
