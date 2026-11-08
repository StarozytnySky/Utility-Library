package org.broken.arrow.library.database.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.broken.arrow.library.database.api.DatabaseFailure;
import org.broken.arrow.library.database.config.DatabaseKind;

import org.broken.arrow.library.database.schema.CollectionDefinition;
import org.broken.arrow.library.database.schema.FieldType;
import org.broken.arrow.library.database.session.WriteSession;

/** Writes via the same borrowed JDBC connection used for reads in the transaction. */
final class JdbcWriteSession extends JdbcReadSession implements WriteSession {
    private final org.broken.arrow.library.database.config.DatabaseKind kind;

    JdbcWriteSession(Connection connection, SqlDialect dialect,
                     org.broken.arrow.library.database.config.DatabaseKind kind) {
        super(connection, dialect);
        this.kind = kind;
    }

    @Override
    public void insert(CollectionDefinition schema, Map<String, Object> record) {
        schema.validateValues(record, true);
        List<String> fields = List.copyOf(record.keySet());
        execute(dialect.insert(schema.name(), fields), fields.stream().map(record::get).toArray(), types(schema, fields));
    }

    @Override
    public boolean insertIfAbsent(CollectionDefinition schema, Map<String, Object> record) {
        schema.validateValues(record, true);
        if (kind == DatabaseKind.SQLITE) {
            // SQLite does not consistently expose a portable SQLState for unique-key conflicts.
            // Target only the primary key: a different unique constraint must still fail loudly.
            List<String> fields = List.copyOf(record.keySet());
            String sql = dialect.insert(schema.name(), fields)
                    + " ON CONFLICT (" + dialect.quote(schema.idField()) + ") DO NOTHING";
            return execute(sql, fields.stream().map(record::get).toArray(), types(schema, fields)) == 1;
        }
        // The savepoint is required by PostgreSQL: after a unique violation the
        // transaction is unusable until the error is rolled back to a savepoint.
        java.sql.Savepoint savepoint;
        try {
            savepoint = connection.setSavepoint();
        } catch (SQLException failure) {
            throw JdbcProvider.wrapSql("Create insert-if-absent savepoint", failure, false);
        }
        try {
            insert(schema, record);
            connection.releaseSavepoint(savepoint);
            return true;
        } catch (DatabaseFailure failure) {
            try {
                connection.rollback(savepoint);
            } catch (SQLException rollback) {
                failure.addSuppressed(rollback);
                throw failure;
            }
            // Only a collision of the actual primary key means the record already exists.
            // Unique constraints on other columns must remain visible to the caller.
            if (failure.reason() == DatabaseFailure.Reason.CONFLICT
                    && exists(schema, record.get(schema.idField()))) {
                return false;
            }
            throw failure;
        } catch (SQLException failure) {
            throw JdbcProvider.wrapSql("Release insert-if-absent savepoint", failure, false);
        }
    }

    @Override
    public void insertBatch(CollectionDefinition schema, List<Map<String, Object>> records, int chunkSize) {
        if (chunkSize < 1 || chunkSize > 10_000) throw new IllegalArgumentException("Invalid batch size");
        if (records.isEmpty()) return;
        // Stable schema-defined field order so every batch entry has identical parameters.
        List<String> columns = schema.fields().stream().map(CollectionDefinition.Field::name).toList();
        FieldType[] columnTypes = types(schema, columns);
        String sql = dialect.insert(schema.name(), columns);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int pending = 0;
            for (Map<String, Object> record : records) {
                schema.validateValues(record, true);
                Object[] values = columns.stream().map(record::get).toArray();
                JdbcValues.bind(statement, values, columnTypes);
                statement.addBatch();
                if (++pending >= chunkSize) {
                    verifyBatch(statement.executeBatch());
                    pending = 0;
                }
            }
            if (pending > 0) verifyBatch(statement.executeBatch());
        } catch (SQLException failure) {
            throw JdbcProvider.wrapSql("Insert batch", failure, false);
        }
    }

    private static void verifyBatch(int[] counts) {
        for (int count : counts) {
            if (count == java.sql.Statement.EXECUTE_FAILED || count == 0) {
                throw new DatabaseFailure(DatabaseFailure.Reason.OPERATION,
                        "Batch insert failed or did not modify a record");
            }
            // SUCCESS_NO_INFO is allowed: JDBC cannot always report exact affected rows.
        }
    }

    @Override
    public void upsert(CollectionDefinition schema, Map<String, Object> record) {
        schema.validateValues(record, true);
        List<String> fields = List.copyOf(record.keySet());
        List<Object> values = new ArrayList<>();
        List<FieldType> types = new ArrayList<>();
        for (String field : fields) {
            values.add(record.get(field));
            types.add(schema.field(field).type());
        }
        if (kind == org.broken.arrow.library.database.config.DatabaseKind.MYSQL
                || kind == org.broken.arrow.library.database.config.DatabaseKind.MARIADB) {
            // Upsert is parameterized even in the MySQL/MariaDB update clause.
            for (String field : fields) {
                if (!field.equals(schema.idField())) {
                    values.add(record.get(field));
                    types.add(schema.field(field).type());
                }
            }
        }
        execute(dialect.upsert(schema, fields), values.toArray(), types.toArray(FieldType[]::new));
    }

    @Override
    public boolean update(CollectionDefinition schema, Map<String, Object> record) {
        schema.validateValues(record, true);
        List<String> fields = record.keySet().stream().filter(field -> !field.equals(schema.idField())).toList();
        if (fields.isEmpty()) return exists(schema, record.get(schema.idField()));
        StringBuilder sql = new StringBuilder("UPDATE ").append(dialect.quote(schema.name())).append(" SET ");
        for (int i = 0; i < fields.size(); i++) {
            if (i != 0) sql.append(", ");
            sql.append(dialect.quote(fields.get(i))).append(" = ?");
        }
        sql.append(" WHERE ").append(dialect.quote(schema.idField())).append(" = ?");
        List<Object> values = new ArrayList<>();
        List<FieldType> types = new ArrayList<>();
        for (String field : fields) {
            values.add(record.get(field));
            types.add(schema.field(field).type());
        }
        values.add(record.get(schema.idField()));
        types.add(schema.field(schema.idField()).type());
        int changed = execute(sql.toString(), values.toArray(), types.toArray(FieldType[]::new));
        // MySQL may report zero affected rows when the update reassigns equal values.
        return changed > 0 || exists(schema, record.get(schema.idField()));
    }

    @Override
    public boolean deleteById(CollectionDefinition schema, Object id) {
        return execute("DELETE FROM " + dialect.quote(schema.name()) + " WHERE "
                        + dialect.quote(schema.idField()) + " = ?", new Object[]{id},
                new FieldType[]{schema.field(schema.idField()).type()}) > 0;
    }

    @Override
    public boolean increment(CollectionDefinition schema, Object id, String field, long amount) {
        requireInteger(schema, field);
        String column = dialect.quote(field);
        String sql = "UPDATE " + dialect.quote(schema.name()) + " SET " + column
                + " = " + column + " + ? WHERE " + dialect.quote(schema.idField())
                + " = ? AND " + column + " IS NOT NULL";
        List<Object> parameters = new ArrayList<>(List.of(amount, id));
        List<FieldType> parameterTypes = new ArrayList<>(List.of(FieldType.LONG,
                schema.field(schema.idField()).type()));
        if (amount > 0) {
            sql += " AND " + column + " <= ?";
            parameters.add(Long.MAX_VALUE - amount);
            parameterTypes.add(FieldType.LONG);
        } else if (amount < 0) {
            sql += " AND " + column + " >= ?";
            parameters.add(Long.MIN_VALUE - amount);
            parameterTypes.add(FieldType.LONG);
        }
        return execute(sql, parameters.toArray(), parameterTypes.toArray(FieldType[]::new)) > 0;
    }

    @Override
    public boolean decrementIfEnough(CollectionDefinition schema, Object id, String field, long amount) {
        requireInteger(schema, field);
        if (amount < 0) throw new IllegalArgumentException("amount must not be negative");
        String sql = "UPDATE " + dialect.quote(schema.name()) + " SET " + dialect.quote(field)
                + " = " + dialect.quote(field) + " - ? WHERE " + dialect.quote(schema.idField())
                + " = ? AND " + dialect.quote(field) + " >= ?";
        return execute(sql, new Object[]{amount, id, amount},
                new FieldType[]{FieldType.LONG, schema.field(schema.idField()).type(), FieldType.LONG}) > 0;
    }

    @Override
    public boolean compareAndSet(CollectionDefinition schema, Object id, String field, long expected, long updated) {
        requireInteger(schema, field);
        String sql = "UPDATE " + dialect.quote(schema.name()) + " SET " + dialect.quote(field)
                + " = ? WHERE " + dialect.quote(schema.idField()) + " = ? AND " + dialect.quote(field)
                + " = ?";
        return execute(sql, new Object[]{updated, id, expected},
                new FieldType[]{FieldType.LONG, schema.field(schema.idField()).type(), FieldType.LONG}) > 0;
    }

    /** Executes a fully bound conditional update, preserving the caller's transaction. */
    @Override
    public long updateMatching(CollectionDefinition schema,
            org.broken.arrow.library.database.repository.RecordQuery query, Map<String, Object> changes) {
        java.util.Objects.requireNonNull(changes, "changes");
        if (query.conditions().isEmpty()) {
            throw new IllegalArgumentException("Conditional updates require a WHERE predicate");
        }
        if (changes.isEmpty()) throw new IllegalArgumentException("No fields to update");
        SqlQuery.Predicate predicate = SqlQuery.predicate(dialect, schema, query);
        List<String> columns = List.copyOf(changes.keySet());
        for (String name : columns) {
            if (name.equals(schema.idField())) throw new IllegalArgumentException("Cannot update identifier");
            schema.validateValue(schema.field(name), changes.get(name));
        }
        StringBuilder sql = new StringBuilder("UPDATE ").append(dialect.quote(schema.name()))
                .append(" SET ");
        for (int idx = 0; idx < columns.size(); idx++) {
            if (idx != 0) sql.append(", ");
            sql.append(dialect.quote(columns.get(idx))).append(" = ?");
        }
        sql.append(predicate.sql());
        List<Object> parameters = new ArrayList<>();
        List<FieldType> types = new ArrayList<>();
        for (String column : columns) {
            parameters.add(changes.get(column));
            types.add(schema.field(column).type());
        }
        parameters.addAll(java.util.Arrays.asList(predicate.parameters()));
        types.addAll(java.util.Arrays.asList(predicate.types()));
        return execute(sql.toString(), parameters.toArray(), types.toArray(FieldType[]::new));
    }

    /**
     * Deletes matching rows through a parameterized predicate on the current transaction.
     *
     * @param schema validated collection descriptor
     * @param query query containing at least one condition
     * @return exact number of deleted rows
     */
    @Override
    public long deleteMatching(CollectionDefinition schema,
            org.broken.arrow.library.database.repository.RecordQuery query) {
        java.util.Objects.requireNonNull(query, "query");
        if (query.conditions().isEmpty()) {
            throw new IllegalArgumentException("Bulk delete requires a WHERE predicate");
        }
        SqlQuery.Predicate predicate = SqlQuery.predicate(dialect, schema, query);
        String sql = "DELETE FROM " + dialect.quote(schema.name()) + predicate.sql();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcValues.bind(statement, predicate.parameters(), predicate.types());
            return statement.executeLargeUpdate();
        } catch (SQLException failure) {
            throw JdbcProvider.wrapSql("Conditional deletion", failure, false);
        }
    }

    private void requireInteger(CollectionDefinition schema, String field) {
        FieldType type = schema.field(field).type();
        if (field.equals(schema.idField()) || (type != FieldType.LONG)) {
            throw new IllegalArgumentException("Atomic integer operations require a mutable integer field");
        }
    }

    private static FieldType[] types(CollectionDefinition schema, List<String> fields) {
        return fields.stream().map(field -> schema.field(field).type()).toArray(FieldType[]::new);
    }

    private int execute(String sql, Object[] values, FieldType[] types) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcValues.bind(statement, values, types);
            return statement.executeUpdate();
        } catch (SQLException failure) {
            throw JdbcProvider.wrapSql("Mutation", failure, false);
        }
    }
}
