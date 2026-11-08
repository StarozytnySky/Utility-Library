package org.broken.arrow.library.database.schema;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Container for multiple {@link StoredIndex} declarations. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface StoredIndexes {
    /**
     * All indexes declared by a model.
     *
     * @return declared annotation value
     */
    StoredIndex[] value();
}
