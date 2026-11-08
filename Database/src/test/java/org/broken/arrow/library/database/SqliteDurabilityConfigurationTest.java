package org.broken.arrow.library.database;

import org.broken.arrow.library.database.config.DatabaseConfiguration;
import org.broken.arrow.library.database.config.DatabaseKind;
import org.broken.arrow.library.database.config.SqliteSynchronousMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Ensures the SQLite durability policy is explicit and cannot inject arbitrary PRAGMAs. */
final class SqliteDurabilityConfigurationTest {
    /** A new SQLite client defaults to FULL durability. */
    @Test
    void defaultsToFullDurability() {
        var config = DatabaseConfiguration.forKind(DatabaseKind.SQLITE, "jdbc:sqlite:database.db").build();
        assertEquals(SqliteSynchronousMode.FULL, config.sqliteSynchronousMode());
    }

    /** A configured NORMAL policy remains available for performance-sensitive deployments. */
    @Test
    void supportsAllowlistedNormalMode() {
        var mode = SqliteSynchronousMode.parse("normal");
        var config = DatabaseConfiguration.forKind(DatabaseKind.SQLITE, "jdbc:sqlite:database.db")
                .sqliteSynchronousMode(mode).build();
        assertEquals(SqliteSynchronousMode.NORMAL, config.sqliteSynchronousMode());
    }

    /** Dangerous or malformed PRAGMA inputs must fail before a driver is opened. */
    @Test
    void rejectsUnsupportedPolicies() {
        assertThrows(IllegalArgumentException.class, () -> SqliteSynchronousMode.parse("OFF"));
        assertThrows(IllegalArgumentException.class,
                () -> SqliteSynchronousMode.parse("FULL; DROP TABLE guilds"));
        assertThrows(NullPointerException.class,
                () -> DatabaseConfiguration.forKind(DatabaseKind.SQLITE, "jdbc:sqlite:database.db")
                        .sqliteSynchronousMode(null));
    }
}
