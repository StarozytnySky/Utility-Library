package org.broken.arrow.library.database.config;

import java.time.Duration;
import java.util.Objects;

/** Immutable provider configuration, with secrets excluded from diagnostic string output. */
public final class DatabaseConfiguration {
    private final DatabaseKind kind;
    private final String address;
    private final String database;
    private final String username;
    private final String password;
    private final int poolSize;
    private final int workerCount;
    private final int queueCapacity;
    private final Duration connectionTimeout;
    private final Duration shutdownTimeout;
    private final int sqliteBusyTimeoutMillis;
    private final boolean sqliteWal;

    private DatabaseConfiguration(Builder builder) {
        kind = Objects.requireNonNull(builder.kind, "kind");
        address = Objects.requireNonNull(builder.address, "address");
        database = builder.database;
        username = builder.username;
        password = builder.password;
        poolSize = builder.poolSize == 0 ? (kind == DatabaseKind.SQLITE ? 1 : 10) : builder.poolSize;
        workerCount = builder.workerCount;
        queueCapacity = builder.queueCapacity;
        connectionTimeout = builder.connectionTimeout;
        shutdownTimeout = builder.shutdownTimeout;
        sqliteBusyTimeoutMillis = builder.sqliteBusyTimeoutMillis;
        sqliteWal = builder.sqliteWal;
        if (poolSize < 1 || poolSize > 128) throw new IllegalArgumentException("poolSize out of range");
        if (workerCount < 1 || workerCount > 64) throw new IllegalArgumentException("workerCount out of range");
        if (queueCapacity < 1 || queueCapacity > 1_000_000) throw new IllegalArgumentException("queueCapacity out of range");
        if (connectionTimeout.toMillis() < 250 || connectionTimeout.toMillis() > 300_000) {
            throw new IllegalArgumentException("connectionTimeout out of range");
        }
        if (shutdownTimeout.isNegative() || shutdownTimeout.isZero()) {
            throw new IllegalArgumentException("shutdownTimeout must be positive");
        }
        if (sqliteBusyTimeoutMillis < 0 || sqliteBusyTimeoutMillis > 300_000) {
            throw new IllegalArgumentException("sqliteBusyTimeoutMillis out of range");
        }
        if (kind == DatabaseKind.SQLITE && poolSize != 1) {
            throw new IllegalArgumentException("SQLite requires poolSize=1 for predictable writer serialization");
        }
        if ((username == null) != (password == null)) {
            throw new IllegalArgumentException("Username and password must be specified together");
        }
        if (kind == DatabaseKind.MONGODB && (database == null || database.isBlank())) {
            throw new IllegalArgumentException("MongoDB database name is required");
        }
        if (kind.isSql() && !address.startsWith("jdbc:" + kind.name().toLowerCase() + ":")
                && !(kind == DatabaseKind.POSTGRESQL && address.startsWith("jdbc:postgresql:"))
                && !(kind == DatabaseKind.MARIADB && address.startsWith("jdbc:mariadb:"))) {
            throw new IllegalArgumentException("JDBC URL does not match selected database kind");
        }
        if (kind == DatabaseKind.MONGODB && !(address.startsWith("mongodb://") || address.startsWith("mongodb+srv://"))) {
            throw new IllegalArgumentException("Invalid MongoDB connection string");
        }
    }

    /** Creates a configuration for the given backend. */
    public static Builder forKind(DatabaseKind kind, String address) {
        return new Builder(kind, address);
    }

    /** Returns the selected database backend. */
    public DatabaseKind kind() { return kind; }
    /** Returns the configured address, including its JDBC or MongoDB scheme. */
    public String address() { return address; }
    /** Returns the logical MongoDB database name. */
    public String database() { return database; }
    /** Returns the optional database username. */
    public String username() { return username; }
    /** Returns the optional database password. */
    public String password() { return password; }
    /** Returns the configured maximum pool size. */
    public int poolSize() { return poolSize; }
    /** Returns the number of blocking database worker threads. */
    public int workerCount() { return workerCount; }
    /** Returns the maximum number of pending database tasks. */
    public int queueCapacity() { return queueCapacity; }
    /** Returns the connection acquisition timeout. */
    public Duration connectionTimeout() { return connectionTimeout; }
    /** Returns the graceful worker shutdown timeout. */
    public Duration shutdownTimeout() { return shutdownTimeout; }
    /** Returns the SQLite busy timeout in milliseconds. */
    public int sqliteBusyTimeoutMillis() { return sqliteBusyTimeoutMillis; }
    /** Returns the whether SQLite WAL mode is enabled. */
    public boolean sqliteWal() { return sqliteWal; }

    /** Never write credentials or connection URIs into diagnostics. */
    @Override
    public String toString() {
        return "DatabaseConfiguration[kind=" + kind + ", poolSize=" + poolSize
                + ", workerCount=" + workerCount + ", queueCapacity=" + queueCapacity + "]";
    }

    /** One-time fluent configuration builder. */
    public static final class Builder {
        private final DatabaseKind kind;
        private final String address;
        private String database;
        private String username;
        private String password;
        private int poolSize;
        private int workerCount = 2;
        private int queueCapacity = 2_048;
        private Duration connectionTimeout = Duration.ofSeconds(10);
        private Duration shutdownTimeout = Duration.ofSeconds(15);
        private int sqliteBusyTimeoutMillis = 5_000;
        private boolean sqliteWal = true;

        private Builder(DatabaseKind kind, String address) {
            this.kind = Objects.requireNonNull(kind, "kind");
            this.address = Objects.requireNonNull(address, "address");
        }

        /** Sets a MongoDB logical database name. */
        public Builder database(String name) { database = name; return this; }

        /** Sets optional connection credentials. */
        public Builder credentials(String user, String secret) {
            username = user;
            password = secret;
            return this;
        }

        /** Sets the connection pool size; SQLite must use exactly one. */
        public Builder poolSize(int size) { poolSize = size; return this; }

        /** Sets the number of blocking I/O workers. */
        public Builder workers(int count, int queue) {
            workerCount = count;
            queueCapacity = queue;
            return this;
        }

        /** Sets the maximum connection acquisition time. */
        public Builder connectionTimeout(Duration value) {
            connectionTimeout = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Sets how long the database waits during graceful shutdown. */
        public Builder shutdownTimeout(Duration value) {
            shutdownTimeout = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Configures SQLite busy handling and persistent WAL mode. */
        public Builder sqliteOptions(boolean wal, int busyTimeoutMillis) {
            sqliteWal = wal;
            sqliteBusyTimeoutMillis = busyTimeoutMillis;
            return this;
        }

        /** Validates and snapshots configuration before opening any network connection. */
        public DatabaseConfiguration build() { return new DatabaseConfiguration(this); }
    }
}
