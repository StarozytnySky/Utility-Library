package org.broken.arrow.library.database;

import org.broken.arrow.library.database.api.DatabaseClient;
import org.broken.arrow.library.database.config.DatabaseConfiguration;
import org.broken.arrow.library.database.config.DatabaseKind;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SQL lease contract for applications migrating to portable record sessions. */
class SqlConnectionLeaseTest {

    /** A leased connection returns to the same managed pool after it is closed. */
    @Test
    void borrowsAndReleasesSqlConnection() throws Exception {
        DatabaseConfiguration configuration = DatabaseConfiguration.forKind(
                DatabaseKind.SQLITE, "jdbc:sqlite::memory:").poolSize(1).build();
        try (DatabaseClient client = DatabaseClient.open(configuration)) {
            try (Connection first = client.borrowSqlConnection();
                 Statement statement = first.createStatement()) {
                statement.execute("CREATE TABLE lease_test (id INTEGER PRIMARY KEY)");
            }
            try (Connection second = client.borrowSqlConnection();
                 Statement statement = second.createStatement();
                 ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM lease_test")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
        }
    }
}
