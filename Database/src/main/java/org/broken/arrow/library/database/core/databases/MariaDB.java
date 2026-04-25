package org.broken.arrow.library.database.core.databases;

import org.broken.arrow.library.database.builders.ConnectionSettings;
import org.broken.arrow.library.database.connection.HikariCP;
import org.broken.arrow.library.database.core.SQLDatabaseQuery;
import org.broken.arrow.library.database.utility.DatabaseCommandConfig;
import org.broken.arrow.library.logging.Logging;
import org.broken.arrow.library.logging.Validate;

import javax.annotation.Nonnull;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;

/**
 * Represents a MariaDB database connection handler, supporting both direct JDBC connections
 * and HikariCP connection pooling if available.
 * <p>
 * This class extends {@link SQLDatabaseQuery} to provide MariaDB-specific connection
 * handling, including automatic database creation and connection management.
 */
public class MariaDB extends SQLDatabaseQuery {
    private final Logging log = new Logging(MariaDB.class);
    private final ConnectionSettings preferences;
    private final String startSQLUrl;
    private final String driver;
    private boolean hasCastException = false;
    private final boolean isHikariAvailable;
    private final HikariCP hikari;

    /**
     * Creates a new MariaDB instance with the given connection settings.
     * This constructor assumes the database already exists and does not attempt to create it.
     *
     * @param preferences The connection settings for the MariaDB database.
     */
    public MariaDB(@Nonnull ConnectionSettings preferences) {
        this(preferences, "com.zaxxer.hikari.HikariConfig");
    }

    /**
     * Creates a new MariaDB instance with the given preferences.
     *
     * @param preferences   The set preference information to connect to the database.
     * @param createDatabase If it shall check and create the database if it not created yet.
     */
    public MariaDB(@Nonnull ConnectionSettings preferences, boolean createDatabase) {
        this(preferences, createDatabase, "com.zaxxer.hikari.HikariConfig");
    }

    /**
     * Creates a new MariaDB instance with the given preferences.
     *
     * @param preferences The set preference information to connect to the database.
     * @param hikariClazz If you shade the lib to your plugin, so for this api shall find it you need to set the path.
     */
    public MariaDB(@Nonnull ConnectionSettings preferences, String hikariClazz) {
        this(preferences, true, hikariClazz);
    }

    /**
     * Creates a new MariaDB instance with the given preferences.
     *
     * @param preferences   The set preference information to connect to the database.
     * @param createDatabase If it shall check and create the database if it not created yet.
     * @param hikariClazz   If you shade the lib to your plugin, so for this api shall find it you need to set the path.
     */
    public MariaDB(@Nonnull ConnectionSettings preferences, boolean createDatabase, String hikariClazz) {
        super(preferences);
        this.preferences = preferences;
        this.isHikariAvailable = isDriverFound(hikariClazz);
        this.setCharacterSet("DEFAULT CHARSET=utf8mb4");
        this.startSQLUrl = "jdbc:mariadb://";
        this.driver = "org.mariadb.jdbc.Driver";
        if (createDatabase) createMissingDatabase();
        if (isHikariAvailable) {
            this.hikari = new HikariCP(this, this.driver);
        } else this.hikari = null;
        connect();
    }

    @Override
    public Connection connect() {
        Validate.checkNotNull(preferences, "You need to set preferences for the database");
        Connection connection = null;
        try {
            if (!hasCastException) {
                connection = this.setupConnection();
            }
            hasCastException = false;
        } catch (SQLRecoverableException exception) {
            hasCastException = true;
            log.log(exception, () -> "Unable to connect to the database. Please try this action again after re-establishing the " +
                    "connection. This issue might be caused by a temporary connectivity problem or a timeout.");

        } catch (SQLException throwable) {
            hasCastException = true;
            log.log(throwable, () -> "Could not connect to the database. Check the error message and make sure the database is running.");
        }
        return connection;
    }

    @Nonnull
    @Override
    public DatabaseCommandConfig databaseConfig() {
        return new DatabaseCommandConfig(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_READ_ONLY);
    }

    /**
     * Sets up and returns a new connection to the MariaDB database.
     * <p>
     * Uses HikariCP if available; otherwise, establishes a direct JDBC connection.
     *
     * @return a new {@link Connection} instance.
     * @throws SQLException if the connection setup fails.
     */
    public Connection setupConnection() throws SQLException {
        Connection connection;

        if (isHikariAvailable && this.hikari != null) {
            connection = this.hikari.getConnection(startSQLUrl);
        } else {
            String databaseName = preferences.getDatabaseName();
            String hostAddress = preferences.getHostAddress();
            String port = preferences.getPort();
            String user = preferences.getUser();
            String password = preferences.getPassword();
            String extra = preferences.getQuery();
            if (extra.isEmpty()) extra = "?useSSL=false&useUnicode=yes&characterEncoding=UTF-8&autoReconnect=" + true;
            connection = DriverManager.getConnection(startSQLUrl + hostAddress + ":" + port + "/" + databaseName + extra, user, password);
        }

        return connection;
    }

    /**
     * Checks if the database specified in the connection settings exists, and creates it if missing.
     * <p>
     * This method establishes a connection to the MariaDB server without selecting a database,
     * then attempts to create the database if it does not already exist.
     */
    public void createMissingDatabase() {
        String databaseName = preferences.getDatabaseName();
        String hostAddress = preferences.getHostAddress();
        String port = preferences.getPort();
        String user = preferences.getUser();
        String password = preferences.getPassword();

        try (Connection createDatabase = DriverManager.getConnection(startSQLUrl + hostAddress + ":" + port + "/?useSSL=false&useUnicode=yes&characterEncoding=UTF-8", user, password)) {
            try (PreparedStatement create = createDatabase.prepareStatement("CREATE DATABASE IF NOT EXISTS " + databaseName)) {
                create.execute();
                close(create);
            }
        } catch (SQLException e) {
            log.log(e, () -> "Failed to connect to the database with the database name: " + databaseName);
        }
    }

    @Override
    public boolean usingHikari() {
        return this.isHikariAvailable;
    }

    @Override
    public boolean hasConnectionFailed() {
        return hasCastException;
    }
}
