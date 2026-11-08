package org.broken.arrow.library.database.config;

import java.util.Locale;
import java.util.Objects;

/**
 * Portable allowlisted SQLite fsync durability policy.
 * OFF is intentionally unsupported because critical guild/bank writes must remain durable.
 */
public enum SqliteSynchronousMode {
    /** Forces a filesystem sync at each relevant commit boundary. */
    FULL,
    /** Reduces fsync frequency in WAL mode; less durable across power loss. */
    NORMAL;

    /**
     * Parses administrator configuration without allowing arbitrary PRAGMA SQL.
     *
     * @param configured declared synchronization level
     * @return validated synchronous mode
     */
    public static SqliteSynchronousMode parse(String configured) {
        Objects.requireNonNull(configured, "configured");
        try {
            return valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("SQLite synchronous mode must be FULL or NORMAL", failure);
        }
    }
}
