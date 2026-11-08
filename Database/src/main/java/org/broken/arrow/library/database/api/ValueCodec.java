package org.broken.arrow.library.database.api;

/**
 * Converts a custom Java value to and from a portable, JSON-compatible tree.
 *
 * <p>Supported storage values are strings, booleans, finite numbers, lists and
 * maps with string keys, recursively. Custom codecs must not return arbitrary
 * Java instances or reference the Minecraft server from a database worker.</p>
 *
 * @param <T> Java value type
 */
public interface ValueCodec<T> {

    /** Converts a Java value into a portable storage representation. */
    Object encode(T value);

    /** Restores a Java value from a portable storage representation. */
    T decode(Object stored);

    /**
     * Annotation sentinel meaning "use the inferred or registered codec".
     * Not intended to be instantiated by plugin code.
     */
    final class Auto implements ValueCodec<Object> {
        /** Creates the annotation sentinel. */
        public Auto() { }

        /** Annotation sentinel: cannot encode values. */
        @Override
        public Object encode(Object value) {
            throw new UnsupportedOperationException("Automatic codec sentinel");
        }

        /** Annotation sentinel: cannot decode values. */
        @Override
        public Object decode(Object stored) {
            throw new UnsupportedOperationException("Automatic codec sentinel");
        }
    }
}
