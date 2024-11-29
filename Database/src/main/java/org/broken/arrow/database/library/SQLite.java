package org.broken.arrow.database.library;

import org.broken.arrow.database.library.builders.ConnectionSettings;
import org.broken.arrow.database.library.builders.tables.SqlCommandComposer;
import org.broken.arrow.database.library.builders.tables.TableWrapper;
import org.broken.arrow.database.library.connection.HikariCP;
import org.broken.arrow.logging.library.Logging;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.broken.arrow.logging.library.Logging.of;

public class SQLite extends Database {
	private final Logging log = new Logging(SQLite.class);
	private final File dbFile;
	private final boolean isHikariAvailable;
	private final HikariCP hikari;
	private boolean hasCastException = false;

	/**
	 * Constructs a new SQLite database instance with the given file path.
	 *
	 * @param parent The parent directory or file path.
	 */
	public SQLite(@Nonnull final String parent) {
		this(parent, "database.db");
	}

	/**
	 * Constructs a new SQLite database instance with the given parent and child paths.
	 *
	 * @param parent The parent directory.
	 * @param child  The child file name.
	 */
	public SQLite(@Nonnull final String parent, @Nullable final String child) {
		this("com.zaxxer.hikari.HikariConfig", parent, child);
	}

	/**
	 * Constructs a new SQLite database instance with the given HikariCP class path, parent, and child.
	 *
	 * @param hikariClazzPath The HikariCP class path.
	 * @param parent          The parent directory.
	 * @param child           The child file name.
	 */
	public SQLite(@Nonnull final String hikariClazzPath, @Nonnull final String parent, @Nullable final String child) {
		this(hikariClazzPath, new DBPath(parent, child));
	}

	private SQLite(@Nonnull final String hikariClazzPath, DBPath dbPath) {
		super(new ConnectionSettings(dbPath.getDbFile().getPath()));
		this.dbFile = dbPath.getDbFile();
		this.isHikariAvailable = isHikariAvailable(hikariClazzPath);
		this.loadDriver("org.sqlite.JDBC");

		if (isHikariAvailable) {
			this.hikari = new HikariCP(this, "org.sqlite.JDBC", "jdbc:sqlite:");
		} else {
			this.hikari = null;
		}

		connect();
	}

	@Override
	public Connection connect() {
		try {
			if (!hasCastException) {
				if (isHikariAvailable && this.hikari != null) {
					return this.hikari.getConnection();
				} else {
					return setupConnection();
				}
			}
		} catch (SQLException ex) {
			this.hasCastException = true;
			log.log(ex, () -> of("Failed to connect to SQLite database"));
		}
		return null;
	}

	@Override
	protected void batchUpdate(@Nonnull final List<SqlCommandComposer> sqlComposer, @Nonnull final TableWrapper... tableWrappers) {
		this.batchUpdate(sqlComposer, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
	}

	public Connection setupConnection() throws SQLException {
		String jdbcUrl = "jdbc:sqlite:" + dbFile.getPath();
		return DriverManager.getConnection(jdbcUrl);
	}

	@Override
	public boolean isHasCastException() {
		return this.hasCastException;
	}

	private static class DBPath {
		private final File dbFile;

		public DBPath(String parent, String child) {
			if (parent != null && child == null) {
				dbFile = new File(parent);
			} else {
				dbFile = new File(parent, child);
			}
		}

		public File getDbFile() {
			return dbFile;
		}
	}

	@Override
	public boolean usingHikari() {
		return this.isHikariAvailable;
	}
}