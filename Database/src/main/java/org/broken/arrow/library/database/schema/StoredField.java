package org.broken.arrow.library.database.schema;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.broken.arrow.library.database.codec.ValueCodec;

/**
 * Optional persisted-field metadata on a Java record component.
 *
 * <p>All settings are checked when the record codec is created. The
 * {@link #codec()} is instantiated exactly once, not per database operation.</p>
 */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.RUNTIME)
public @interface StoredField {

    /**
     * Overrides the database column/document field name; empty uses the component name.
     *
     * @return declared name of the field, index, or collection
     */
    String name() default "";

    /**
     * Stores a String as an unrestricted SQL TEXT/CLOB/LONGTEXT column.
     * Useful for serialized inventories, audit payloads and other content that exceeds
     * the bounded H2 VARCHAR mapping. Large text cannot be indexed or used as a key.
     *
     * @return whether the field uses the portable large-text storage type
     */
    boolean longText() default false;

    /**
     * Whether this field may contain a null value.
     *
     * @return whether null is permitted in the field
     */
    boolean nullable() default false;

    /**
     * Optional stateless custom codec with an accessible no-argument constructor.
     *
     * @return class describing the configured value type
     */
    Class<? extends ValueCodec<?>> codec() default ValueCodec.Auto.class;
}
