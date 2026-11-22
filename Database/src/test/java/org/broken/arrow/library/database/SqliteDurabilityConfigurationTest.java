package org.broken.arrow.library.database;

import org.broken.arrow.library.database.config.DatabaseConfiguration;
import org.broken.arrow.library.database.config.DatabaseKind;
import org.broken.arrow.library.database.config.SqliteSynchronousMode;
import org.broken.arrow.library.database.jdbc.JdbcProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

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

    /**
     * Confirms that disabling WAL explicitly restores rollback journaling for an existing database.
     * The transition must preserve committed records and the journal mode must remain DELETE.
     *
     * @param tempDir temporary directory isolated from other tests
     * @throws SQLException if the JDBC operations fail
     */
    @Test
    void switchesExistingWalDatabaseToDelete(@TempDir Path tempDir) throws SQLException {
        String address = "jdbc:sqlite:" + tempDir.resolve("journal.db");
        var walConfiguration = DatabaseConfiguration.forKind(DatabaseKind.SQLITE, address)
                .sqliteOptions(true, 5_000).build();
        var deleteConfiguration = DatabaseConfiguration.forKind(DatabaseKind.SQLITE, address)
                .sqliteOptions(false, 5_000).build();

        try (JdbcProvider ignored = new JdbcProvider(walConfiguration)) {
            assertEquals("wal", journalMode(address));
            try (Connection connection = DriverManager.getConnection(address);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE entries (value TEXT NOT NULL)");
                statement.execute("INSERT INTO entries (value) VALUES ('preserved')");
            }
        }

        try (JdbcProvider ignored = new JdbcProvider(deleteConfiguration)) {
            assertEquals("delete", journalMode(address));
            try (Connection connection = DriverManager.getConnection(address);
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT value FROM entries")) {
                assertTrue(rows.next());
                assertEquals("preserved", rows.getString(1));
            }
        }

        assertEquals("delete", journalMode(address));
    }

    /**
     * Reads SQLite's effective journal mode from an independent connection.
     *
     * @param address JDBC URL of the SQLite database
     * @return the SQLite-reported journal mode
     * @throws SQLException if the journal mode cannot be read
     */
    private static String journalMode(String address) throws SQLException {
        try (Connection connection = DriverManager.getConnection(address);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA journal_mode")) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }
}
