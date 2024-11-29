package org.broken.arrow.database.library.connection;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.broken.arrow.database.library.Database;
import org.broken.arrow.database.library.builders.ConnectionSettings;
import org.broken.arrow.logging.library.Logging;

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
	private final Database database;
	private final String driver;
	private HikariDataSource hikari;

	// Configuration parameters
	private final String driverConnection;
	private final String hostAddress;
	private final String databaseName;

	public HikariCP(@Nonnull Database database, String driver, String driverConnection) {
		this.database = database;
		this.driver = driver;
		this.driverConnection = driverConnection;

		// Initialize configuration parameters
		ConnectionSettings connectionSettings = this.database.getConnectionSettings();
		this.hostAddress = connectionSettings.getHostAddress();
		this.databaseName = connectionSettings.getDatabaseName();

		// Initialize the data source
		initializeDataSource();
	}

	private synchronized void initializeDataSource() {
		if (this.hikari != null && !this.hikari.isClosed()) {
			return; // DataSource is already initialized
		}

		HikariConfig config = getHikariConfig();

		// Set additional configurations from the database instance
		int poolSize = this.database.getMaximumPoolSize();
		if (poolSize > 0) {
			config.setMaximumPoolSize(poolSize);
		}

		long connectionTimeout = this.database.getConnectionTimeout();
		if (connectionTimeout > 0) {
			config.setConnectionTimeout(connectionTimeout);
		}

		long idleTimeout = this.database.getIdleTimeout();
		if (idleTimeout > 0) {
			config.setIdleTimeout(idleTimeout);
		}

		int minIdleTimeout = this.database.getMinimumIdle();
		if (minIdleTimeout > 0) {
			config.setMinimumIdle(minIdleTimeout);
		}

		long maxLifeTime = this.database.getMaxLifeTime();
		if (maxLifeTime > 0) {
			config.setMaxLifetime(maxLifeTime);
		}

		config.setLeakDetectionThreshold(10_000); // Set threshold to 10 seconds

		this.hikari = new HikariDataSource(config);

		// Turn off logs if desired
		turnOfLogs();
	}

	@Nonnull
	private HikariConfig getHikariConfig() {
		final ConnectionSettings connectionSettings = this.database.getConnectionSettings();
		String jdbcUrl;
		String user = connectionSettings.getUser();
		String password = connectionSettings.getPassword();
		String extra = connectionSettings.getQuery();
		HikariConfig config = new HikariConfig();

		// Determine the database type based on the driver class name
		String driverClassName = this.driver.toLowerCase();

		if (driverClassName.contains("mysql")) {
			// MySQL configuration
			String hostAddress = connectionSettings.getHostAddress();
			String port = connectionSettings.getPort();
			String databaseName = connectionSettings.getDatabaseName();

			if (extra.isEmpty()) {
				extra = "?useSSL=false&useUnicode=yes&characterEncoding=UTF-8&autoReconnect=true";
			}

			jdbcUrl = "jdbc:mysql://" + hostAddress + ":" + port + "/" + databaseName + extra;

			config.setJdbcUrl(jdbcUrl);
			config.setUsername(user);
			config.setPassword(password);
			config.setDriverClassName(this.driver);

		} else if (driverClassName.contains("sqlite")) {
			// SQLite configuration
			String databasePath = connectionSettings.getDatabaseName(); // For SQLite, databaseName holds the file path

			jdbcUrl = "jdbc:sqlite:" + databasePath;

			config.setJdbcUrl(jdbcUrl);
			config.setDriverClassName(this.driver);
			// SQLite does not require username and password

		} else if (driverClassName.contains("h2")) {
			// H2 configuration
			String databasePath = connectionSettings.getDatabaseName(); // For H2, databaseName holds the file path

			if (extra.isEmpty()) {
				extra = ";AUTO_RECONNECT=TRUE";
			}

			jdbcUrl = "jdbc:h2:" + databasePath + extra;

			config.setJdbcUrl(jdbcUrl);
			config.setDriverClassName(this.driver);

			// H2 may or may not require username and password
			if (user != null && !user.isEmpty()) {
				config.setUsername(user);
			}
			if (password != null && !password.isEmpty()) {
				config.setPassword(password);
			}

		} else {
			throw new IllegalArgumentException("Unsupported database type: " + this.driver);
		}

		// Set additional configurations from the database instance
		if (this.database.getMaximumPoolSize() > 0) {
			config.setMaximumPoolSize(this.database.getMaximumPoolSize());
		}
		if (this.database.getConnectionTimeout() > 0) {
			config.setConnectionTimeout(this.database.getConnectionTimeout());
		}
		if (this.database.getIdleTimeout() > 0) {
			config.setIdleTimeout(this.database.getIdleTimeout());
		}
		if (this.database.getMinimumIdle() > 0) {
			config.setMinimumIdle(this.database.getMinimumIdle());
		}
		if (this.database.getMaxLifeTime() > 0) {
			config.setMaxLifetime(this.database.getMaxLifeTime());
		}

		config.setLeakDetectionThreshold(10_000); // Set threshold to 10 seconds

		return config;
	}

	public Connection getConnection() throws SQLException {
		if (this.hikari == null || this.hikari.isClosed()) {
			initializeDataSource();
		}
		return this.hikari.getConnection();
	}

	public void close() {
		if (this.hikari != null && !this.hikari.isClosed()) {
			this.hikari.close();
		}
	}

	public void turnOfLogs() {
		Configurator.setAllLevels("com.zaxxer.hikari", Level.WARN);

//		Configurator.setAllLevels("com.zaxxer.hikari.pool.PoolBase", Level.WARN);
//		Configurator.setAllLevels("com.zaxxer.hikari.HikariDataSource", Level.WARN);
//		Configurator.setAllLevels("com.zaxxer.hikari.pool.HikariPool", Level.WARN);
	}
}
