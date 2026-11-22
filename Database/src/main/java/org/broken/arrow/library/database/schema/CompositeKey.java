package org.broken.arrow.library.database.schema;

import java.util.List;
import java.util.Objects;

/** Immutable ordered identifier for a schema with a composite primary key. */
public record CompositeKey(List<Object> values) {
    /** Checks that all components are present and non-null. */
    public CompositeKey {
        values = List.copyOf(Objects.requireNonNull(values, "values"));
        if (values.isEmpty()) throw new IllegalArgumentException("Composite key cannot be empty");
    }

    /** Creates a detached key in declaration order. */
    public static CompositeKey of(Object... values) {
        return new CompositeKey(List.of(values));
    }
}
