package org.broken.arrow.library.database.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Optional persisted-field metadata on a Java record component.
 *
 * <p>All settings are checked when the record codec is created. The
 * {@link #codec()} is instantiated exactly once, not per database operation.</p>
 */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.RUNTIME)
public @interface StoredField {

    /** Overrides the database column/document field name; empty uses the component name. */
    String name() default "";

    /** Whether this field may contain a null value. */
    boolean nullable() default false;

    /** Optional stateless custom codec with an accessible no-argument constructor. */
    Class<? extends ValueCodec<?>> codec() default ValueCodec.Auto.class;
}
