package org.broken.arrow.library.database.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.broken.arrow.library.database.api.CollectionDefinition;
import org.broken.arrow.library.database.api.DatabaseConfiguration;
import org.broken.arrow.library.database.api.DatabaseFailure;
import org.broken.arrow.library.database.api.DatabaseKind;
import org.broken.arrow.library.database.api.DatabaseProvider;
import org.broken.arrow.library.database.api.DatabaseWork;
import org.broken.arrow.library.database.api.ReadSession;
import org.broken.arrow.library.database.api.WriteSession;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.Objects;

/** HikariCP-backed JDBC provider with explicit ownership and atomic transaction boundaries. */
public final class JdbcProvider implements DatabaseProvider {
    private final DatabaseKind kind;
    private final HikariDataSource pool;
    private final SqlDialect dialect;

    /** Configures and opens a JDBC pool, validating the connection before returning. */
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
        } catch (SQLException failure) {
            throw wrapSql("Create collection schema", failure, false);
        }
    }

    /** Normalizes error classification without leaking SQL values or secrets. */
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
