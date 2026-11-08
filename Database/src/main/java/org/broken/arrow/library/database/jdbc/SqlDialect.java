package org.broken.arrow.library.database.jdbc;

import java.util.List;
import java.util.stream.Collectors;

import org.broken.arrow.library.database.config.DatabaseKind;
import org.broken.arrow.library.database.schema.CollectionDefinition;
import org.broken.arrow.library.database.schema.FieldType;
import org.broken.arrow.library.database.schema.Identifiers;

/** Centralizes SQL grammar differences; portable code does not inspect the database type. */
public final class SqlDialect {
    private final DatabaseKind kind;

    /**
     * Binds the dialect to a supported JDBC database.
     *
     * @param kind requested database engine
     */
    public SqlDialect(DatabaseKind kind) {
        if (!kind.isSql()) throw new IllegalArgumentException("MongoDB is not a SQL dialect");
        this.kind = kind;
    }

    /**
     * Quotes only validated identifiers, with the backend's native quote character.
     *
     * @param identifier value of identifier used by this operation
     * @return validated and escaped SQL identifier for the selected dialect
     */
    public String quote(String identifier) {
        char character = kind == DatabaseKind.MYSQL || kind == DatabaseKind.MARIADB ? '`' : '"';
        return character + Identifiers.require(identifier) + character;
    }

    /**
     * Builds an identifier-safe insert template.
     *
     * @param table table name validated against the schema
     * @param fields ordered column names of the operation
     * @return parameterized SQL INSERT statement
     */
    public String insert(String table, List<String> fields) {
        if (fields.isEmpty()) throw new IllegalArgumentException("No insert fields");
        return "INSERT INTO " + quote(table) + " (" + fields.stream().map(this::quote)
                .collect(Collectors.joining(", ")) + ") VALUES ("
                + fields.stream().map(unused -> "?").collect(Collectors.joining(", ")) + ")";
    }

    /**
     * Generates portable upsert syntax with a deterministic conflict target.
     *
     * @param schema definition of the target table or collection
     * @param fields ordered column names of the operation
     * @return dialect-specific, parameterized UPSERT statement
     */
    public String upsert(CollectionDefinition schema, List<String> fields) {
        if (kind == DatabaseKind.H2) {
            // H2 uses MERGE, not PostgreSQL's INSERT ... ON CONFLICT syntax.
            return "MERGE INTO " + quote(schema.name()) + " ("
                    + fields.stream().map(this::quote).collect(Collectors.joining(", ")) + ") KEY ("
                    + quote(schema.idField()) + ") VALUES ("
                    + fields.stream().map(unused -> "?").collect(Collectors.joining(", ")) + ")";
        }
        String sql = insert(schema.name(), fields);
        List<String> mutable = fields.stream().filter(f -> !f.equals(schema.idField())).toList();
        if (kind == DatabaseKind.MYSQL || kind == DatabaseKind.MARIADB) {
            if (mutable.isEmpty()) {
                return sql + " ON DUPLICATE KEY UPDATE " + quote(schema.idField())
                        + " = " + quote(schema.idField());
            }
            // Bind the new values again rather than using deprecated MySQL VALUES().
            return sql + " ON DUPLICATE KEY UPDATE " + mutable.stream()
                    .map(field -> quote(field) + " = ?")
                    .collect(Collectors.joining(", "));
        }
        String conflict = " ON CONFLICT (" + quote(schema.idField()) + ") ";
        if (mutable.isEmpty()) return sql + conflict + "DO NOTHING";
        return sql + conflict + "DO UPDATE SET " + mutable.stream()
                .map(field -> quote(field) + " = excluded." + quote(field))
                .collect(Collectors.joining(", "));
    }

    /**
     * Compiles a conservative CREATE TABLE IF NOT EXISTS statement without schema alterations.
     *
     * @param schema definition of the target table or collection
     * @return CREATE TABLE statement for the declared schema
     */
    public String createTable(CollectionDefinition schema) {
        return "CREATE TABLE IF NOT EXISTS " + quote(schema.name()) + " ("
                + schema.fields().stream().map(field -> {
                    String column = quote(field.name()) + " "
                            + sqlType(field.type(), field.name().equals(schema.idField()),
                                    schema.indexes().stream().anyMatch(index -> index.columns().contains(field.name())));
                    if (!field.nullable() || field.name().equals(schema.idField())) column += " NOT NULL";
                    if (field.name().equals(schema.idField())) column += " PRIMARY KEY";
                    return column;
                }).collect(Collectors.joining(", ")) + ")";
    }

    /**
     * Compiles one validated secondary index for bootstrap.
     *
     * @param schema definition of the target table or collection
     * @param index index definition or index name to verify
     * @return CREATE INDEX statement for the declared index
     */
    public String createIndex(CollectionDefinition schema, CollectionDefinition.Index index) {
        if (!schema.indexes().contains(index)) throw new IllegalArgumentException("Index not in schema");
        return "CREATE " + (index.unique() ? "UNIQUE " : "") + "INDEX " + quote(index.name())
                + " ON " + quote(schema.name()) + " (" + index.columns().stream()
                .map(this::quote).collect(Collectors.joining(", ")) + ")";
    }

    private String sqlType(FieldType type, boolean key, boolean indexed) {
        return switch (type) {
            case STRING -> key || indexed ? "VARCHAR(191)" : (kind == DatabaseKind.H2 ? "VARCHAR(4096)" : "TEXT");
            case UUID -> "VARCHAR(36)";
            case LONG, INSTANT -> "BIGINT";
            case INTEGER -> "INTEGER";
            case BOOLEAN -> kind == DatabaseKind.SQLITE ? "INTEGER" : "BOOLEAN";
            case DOUBLE -> kind == DatabaseKind.POSTGRESQL ? "DOUBLE PRECISION" : "DOUBLE";
            case DECIMAL -> "DECIMAL(38, 10)";
            case JSON -> kind == DatabaseKind.H2 ? "CLOB" : (kind == DatabaseKind.MYSQL
                    || kind == DatabaseKind.MARIADB ? "LONGTEXT" : "TEXT");
            case BYTES -> kind == DatabaseKind.POSTGRESQL ? "BYTEA"
                    : (kind == DatabaseKind.MYSQL || kind == DatabaseKind.MARIADB ? "LONGBLOB" : "BLOB");
        };
    }
}
