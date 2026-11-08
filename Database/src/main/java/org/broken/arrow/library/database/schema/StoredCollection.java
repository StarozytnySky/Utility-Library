package org.broken.arrow.library.database.schema;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.broken.arrow.library.database.api.DatabaseManager;

/**
 * Declares a portable SQL table or MongoDB collection for a Java record.
 *
 * <p>Enables {@link DatabaseManager#register(Class)} without guessing table
 * names or identifying a primary key based on naming conventions.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface StoredCollection {
    /**
     * The SQL table or MongoDB collection name.
     *
     * @return declared annotation value
     */
    String value();

    /**
     * The Java record component containing the stable primary key.
     *
     * @return string representation of the requested value
     */
    String idField() default "id";
}
