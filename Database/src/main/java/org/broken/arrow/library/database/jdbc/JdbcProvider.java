package org.broken.arrow.library.database.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.Objects;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.broken.arrow.library.database.api.DatabaseFailure;
import org.broken.arrow.library.database.config.DatabaseConfiguration;
import org.broken.arrow.library.database.config.DatabaseKind;
import org.broken.arrow.library.database.schema.CollectionDefinition;
import org.broken.arrow.library.database.session.DatabaseWork;
import org.broken.arrow.library.database.session.ReadSession;
import org.broken.arrow.library.database.session.WriteSession;

import org.broken.arrow.library.database.spi.DatabaseProvider;

/** HikariCP-backed JDBC provider with explicit ownership and atomic transaction boundaries. */
public final class JdbcProvider implements DatabaseProvider {
    private final DatabaseKind kind;
    private final HikariDataSource pool;
    private final SqlDialect dialect;

    /**
     * Configures and opens a JDBC pool, validating the connection before returning.
     *
     * @param config validated connection and worker configuration
     */
    public JdbcProvider(DatabaseConfiguration config) {
        kind = config.kind();
        if (!kind.isSql()) throw new IllegalArgumentException("JDBC provider cannot open MongoDB");
        dialect = new SqlDialect(kind);
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("Utility-Database-" + kind + "-" + Integer.toHexString(System.identityHashCode(this)));
        hikari.setJdbcUrl(config.address());
        hikari.setMaximumPoolSize(config.poolSize());
        hikari.setMinimumIdle(Math.min(config.poolSize(), kind == DatabaseKind.SQLITE ? 1 : 2));
        hikari.setConnectionTimeout(config.connectionTimeout().toMillis());
        hikari.setAutoCommit(true);
        hikari.setReadOnly(false);
        if (config.username() != null) hikari.setUsername(config.username());
        if (config.password() != null) hikari.setPassword(config.password());
        if (kind == DatabaseKind.SQLITE) {
            hikari.setConnectionInitSql("PRAGMA foreign_keys=ON");
            hikari.addDataSourceProperty("busy_timeout", config.sqliteBusyTimeoutMillis());
            // xerial applies SQLite PRAGMA options to every new physical connection.
            hikari.addDataSourceProperty("synchronous", config.sqliteSynchronousMode().name());
        }
        try {
            pool = new HikariDataSource(hikari);
            try (Connection connection = pool.getConnection(); Statement check = connection.createStatement()) {
                if (kind == DatabaseKind.SQLITE && config.sqliteWal()
                        && !config.address().contains(":memory:") && !config.address().contains("mode=memory")) {
                    check.execute("PRAGMA journal_mode=WAL");
                } else {
                    check.execute("SELECT 1");
                }
            } catch (SQLException failure) {
                pool.close();
                throw failure;
            }
        } catch (SQLException failure) {
            throw wrapSql("Open JDBC pool", failure, false);
        } catch (RuntimeException failure) {
            throw new DatabaseFailure(DatabaseFailure.Reason.CONNECTION, "Could not start JDBC pool", failure);
        }
    }

    @Override
    public DatabaseKind kind() { return kind; }

    @Override
    public boolean supportsTransactions() { return true; }

    @Override
    public <T> T read(DatabaseWork<ReadSession, T> work) {
        Objects.requireNonNull(work, "work");
        try (Connection connection = pool.getConnection()) {
            return work.execute(new JdbcReadSession(connection, dialect));
        } catch (DatabaseFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw failure instanceof SQLException sql ? wrapSql("JDBC read", sql, false)
                    : new DatabaseFailure(DatabaseFailure.Reason.OPERATION, "JDBC read failed", failure);
        }
    }

    @Override
    public <T> T transaction(DatabaseWork<WriteSession, T> work) {
        Objects.requireNonNull(work, "work");
        try (Connection connection = pool.getConnection()) {
            if (!connection.getAutoCommit()) {
                throw new DatabaseFailure(DatabaseFailure.Reason.OPERATION, "Pool returned an active transaction");
            }
            connection.setAutoCommit(false);
            boolean committed = false;
            try {
                T result = work.execute(new JdbcWriteSession(connection, dialect, kind));
                try {
                    connection.commit();
                    committed = true;
                } catch (SQLException uncertain) {
                    // An exception at COMMIT may occur after the server has persisted the write.
                    // Never retry non-idempotent operations automatically.
                    throw new DatabaseFailure(DatabaseFailure.Reason.COMMIT_OUTCOME_UNKNOWN,
                            "JDBC COMMIT returned an uncertain outcome; reconcile via operation ID", uncertain);
                }
                return result;
            } catch (Throwable failure) {
                if (!committed) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackFailure) {
                        failure.addSuppressed(rollbackFailure);
                    }
                }
                throw failure;
            } finally {
                if (committed) {
                    try {
                        connection.setAutoCommit(true);
                    } catch (SQLException resetFailure) {
                        // A successfully committed write must not be reported as failed due to cleanup.
                        // HikariCP owns connection reset/disposal on close.
                    }
                }
            }
        } catch (DatabaseFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw failure instanceof SQLException sql ? wrapSql("JDBC transaction", sql, false)
                    : new DatabaseFailure(DatabaseFailure.Reason.OPERATION, "Transaction failed", failure);
        }
    }

    @Override
    public <T> T writeOne(DatabaseWork<WriteSession, T> work) {
        // Even a single SQL mutation must have a confirmed COMMIT outcome.
        return transaction(work);
    }

    @Override
    public void createIfAbsent(CollectionDefinition schema) {
        Objects.requireNonNull(schema, "schema");
        // DDL is not transactional on all supported JDBC engines (notably MySQL).
        // Borrow exactly one connection: SQLite pools are intentionally size one.
        try (Connection connection = pool.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(dialect.createTable(schema));
            for (CollectionDefinition.Index index : schema.indexes()) {
                if (!indexExists(connection, schema.name(), index.name())) {
                    statement.execute(dialect.createIndex(schema, index));
                }
            }
        } catch (SQLException failure) {
            throw wrapSql("Create collection schema", failure, false);
        }
    }

    /**
     * Checks provider catalog metadata without assuming CREATE INDEX IF NOT EXISTS support.
     *
     * @param connection active JDBC connection owned by the current operation
     * @param table table name validated against the schema
     * @param name validated name of the resource
     * @return true if the requested condition is satisfied
     */
    private static boolean indexExists(Connection connection, String table, String name) throws SQLException {
        java.sql.DatabaseMetaData metadata = connection.getMetaData();
        // Our SQL dialect quotes identifiers, so try their exact case first.
        if (catalogContainsIndex(metadata, connection.getCatalog(), table, name)) return true;
        // Some catalog implementations expose normalized names even for quoted tables.
        String normalized = metadata.storesUpperCaseIdentifiers() ? table.toUpperCase(java.util.Locale.ROOT)
                : metadata.storesLowerCaseIdentifiers() ? table.toLowerCase(java.util.Locale.ROOT) : table;
        return !normalized.equals(table)
                && catalogContainsIndex(metadata, connection.getCatalog(), normalized, name);
    }

    /**
     * Reads one database catalog index listing and always closes its cursor.
     *
     * @param metadata JDBC metadata used to inspect existing database objects
     * @param catalog optional catalog containing the target table
     * @param table table name validated against the schema
     * @param index index definition or index name to verify
     * @return true if the requested condition is satisfied
     */
    private static boolean catalogContainsIndex(java.sql.DatabaseMetaData metadata, String catalog,
            String table, String index) throws SQLException {
        try (java.sql.ResultSet result = metadata.getIndexInfo(catalog, null, table, false, false)) {
            while (result.next()) {
                if (index.equalsIgnoreCase(result.getString("INDEX_NAME"))) return true;
            }
        }
        return false;
    }

    /**
     * Normalizes error classification without leaking SQL values or secrets.
     *
     * @param operation name of the database operation used for diagnostics
     * @param failure underlying error that caused the operation to fail
     * @param commit whether a commit outcome must be classified as uncertain
     * @return computed result of the requested operation
     */
    static DatabaseFailure wrapSql(String operation, SQLException failure, boolean commit) {
        String state = failure.getSQLState();
        DatabaseFailure.Reason reason = commit ? DatabaseFailure.Reason.COMMIT_OUTCOME_UNKNOWN
                : failure instanceof SQLIntegrityConstraintViolationException
                || (state != null && state.startsWith("23"))
                ? DatabaseFailure.Reason.CONFLICT : DatabaseFailure.Reason.OPERATION;
        return new DatabaseFailure(reason, operation + " failed (SQLState=" + state
                + ", code=" + failure.getErrorCode() + ")", failure);
    }

    /** Closes the owned pool. No process-global logging settings are changed. */
    @Override
    public void close() { pool.close(); }
}
