package org.broken.arrow.library.database.schema;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares an indexed set of persisted columns on a database record. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(StoredIndexes.class)
public @interface StoredIndex {
    /**
     * Provider-independent index name, unique within the database namespace.
     *
     * @return declared name of the field, index, or collection
     */
    String name();

    /**
     * Persisted column names (after applying {@link StoredField#name()}).
     *
     * @return ordered columns in the index
     */
    String[] columns();

    /**
     * Whether the backend must reject duplicated non-null key tuples.
     *
     * @return whether the index enforces uniqueness
     */
    boolean unique() default false;
}
