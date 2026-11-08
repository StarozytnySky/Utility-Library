package org.broken.arrow.library.database.api;

/** Identifies a database engine without leaking driver-specific APIs into repositories. */
public enum DatabaseKind {
    SQLITE, H2, MYSQL, MARIADB, POSTGRESQL, MONGODB;

    /** Returns whether the provider uses JDBC and supports native SQL. */
    public boolean isSql() {
        return this != MONGODB;
    }
}
