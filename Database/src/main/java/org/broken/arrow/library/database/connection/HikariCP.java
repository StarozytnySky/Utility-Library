package org.broken.arrow.library.database.connection;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.broken.arrow.library.database.core.Database;
import org.broken.arrow.library.database.builders.ConnectionSettings;
import org.broken.arrow.library.logging.Logging;

import javax.annotation.Nonnull;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * This class manages the HikariCP connection pool if it is available.
 * It consolidates all configurations and operations related to HikariCP,
 * allowing for seamless integration and usage of the default connection pool.
 * <p>
 * If you intend to use the default connection pool provided by HikariCP,
 * encapsulate all HikariCP-related functionality within this class.
 * <p>
 * Note: Ensure that HikariCP is included in your project dependencies
 * for this class to function correctly.
 */
public class HikariCP {
    private final Logging log = new Logging(HikariCP.class);
    private volatile HikariDataSource hikari;
    private final Database database;
    private final String driver;

    /**
     * Constructs a new HikariCP manager for the specified database and JDBC driver.
     *
     * @param database the database instance providing connection settings and configuration
     * @param driver the fully qualified class name of the JDBC driver
     */
    public HikariCP(@Nonnull Database database, String driver) {
        this.database = database;
        this.driver = driver;
    }
    /**
     * Retrieves a connection from the HikariCP pool configured with the provided driver connection prefix.
     * If the pool does not exist or the configuration has changed, a new pool is created.
     *
     * @param driverConnection the JDBC connection prefix (e.g., "jdbc:mysql://")
     * @return a {@link Connection} instance from the pool
     * @throws SQLException if a database access error occurs or the pool cannot be created
     */
    public Connection getConnection(String driverConnection) throws SQLException {
        final ConnectionSettings connectionSettings = this.database.getConnectionSettings();
        final String databaseName = connectionSettings.getDatabaseName();
        final String hostAddress = connectionSettings.getHostAddress();

        final HikariConfig config = getHikariConfig(driverConnection, hostAddress, databaseName);

        final int poolSize = this.database.getMaximumPoolSize();
        if (poolSize > 0)
            // Default is usually 32
            config.setMaximumPoolSize(poolSize);

        long connectionTimeout = this.database.getConnectionTimeout();
        if (connectionTimeout > 0)
            config.setConnectionTimeout(connectionTimeout);

        long idleTimeout = this.database.getIdleTimeout();
        if (idleTimeout > 0)
            config.setIdleTimeout(idleTimeout);

        int minIdleTimeout = this.database.getMinimumIdle();
        if (idleTimeout > 0)
            config.setMinimumIdle(minIdleTimeout);

        long maxLifeTime = this.database.getMaxLifeTime();
        if (maxLifeTime > 0)
            config.setMaxLifetime(maxLifeTime);


        return createPoolIfSetDataNotMatch(config);
    }

    /**
     * Creates or recreates the connection pool if the current pool is closed or
     * its configuration differs from the given config.
     *
     * @param config the {@link HikariConfig} to configure the pool
     * @return a {@link Connection} from the pool
     * @throws SQLException if a database access error occurs
     */
    private Connection createPoolIfSetDataNotMatch(HikariConfig config) throws SQLException {
        synchronized (this) {
            if (this.hikari == null || this.hikari.isClosed()) {
                this.hikari = new HikariDataSource(config);
            }

            boolean needRecreate = !this.hikari.getJdbcUrl().equals(config.getJdbcUrl()) ||
                    !this.hikari.getUsername().equals(config.getUsername()) ||
                    !this.hikari.getPassword().equals(config.getPassword());

            if (needRecreate) {
                try {
                    this.hikari.close();
                } catch (Exception e) {
                    log.log(e, () -> "Failed to close the connection pool. Continuing with recreation.");
                }
                this.hikari = new HikariDataSource(config);
            }
            if (this.hikari.isClosed()) {
                log.log(java.util.logging.Level.WARNING, () -> "Failed to initialize HikariDataSource: The connection pool is closed. Unable to proceed with the connection process. Try again later.");
                return null;
            }
        }
        return this.hikari.getConnection();
    }

    /**
     * Builds a {@link HikariConfig} instance based on the connection parameters.
     *
     * @param driverConnection the JDBC connection prefix (e.g., "jdbc:mysql://")
     * @param hostAddress the database host address
     * @param databaseName the database name
     * @return the configured {@link HikariConfig} object
     */
    @Nonnull
    private HikariConfig getHikariConfig(String driverConnection, String hostAddress, String databaseName) {
        final ConnectionSettings connectionSettings = this.database.getConnectionSettings();
        String port = connectionSettings.getPort();
        String user = connectionSettings.getUser();
        String password = connectionSettings.getPassword();
        String extra = connectionSettings.getQuery();

        if (extra.isEmpty())
            extra = "?useSSL=false&useUnicode=yes&characterEncoding=UTF-8&autoReconnect=" + true;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(driverConnection + hostAddress + ":" + port + "/" + databaseName + extra);
        config.setUsername(user);
        config.setPassword(password);
        config.setDriverClassName(this.driver);
        return config;
    }

    /**
     * Gets a connection for file-based databases (or similar), initializing the pool accordingly.
     * Closes any existing pool connection before creating a new one.
     *
     * @param driverConnection the JDBC connection prefix for the file database
     * @return a {@link Connection} instance
     * @throws SQLException if a database access error occurs
     */
    public Connection getFileConnection(String driverConnection) throws SQLException {
        final ConnectionSettings connectionSettings = this.database.getConnectionSettings();
        String hostAddress = connectionSettings.getHostAddress();
        if (this.hikari != null)
            this.hikari.getConnection().close();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(driverConnection + hostAddress);
        config.setDriverClassName(this.driver);
        this.hikari = new HikariDataSource(config);
        turnOfLogs();
        return this.hikari.getConnection();
    }

    /**
     * Disables verbose logging for the HikariCP connection pool internals,
     * setting the logging level to WARN to reduce noise in logs.
     */
    public void turnOfLogs() {
        Configurator.setAllLevels("com.zaxxer.hikari.pool.PoolBase", Level.WARN);
        Configurator.setAllLevels("com.zaxxer.hikari.HikariDataSource", Level.WARN);
        Configurator.setAllLevels("com.zaxxer.hikari.pool.HikariPool", Level.WARN);
    }
}
