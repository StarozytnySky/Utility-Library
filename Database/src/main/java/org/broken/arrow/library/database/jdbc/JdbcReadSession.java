package org.broken.arrow.library.database.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.broken.arrow.library.database.repository.RecordQuery;

import org.broken.arrow.library.database.schema.CollectionDefinition;
import org.broken.arrow.library.database.schema.FieldType;

import org.broken.arrow.library.database.session.ReadSession;

/** JDBC read adapter. The borrowed connection is never returned to callers. */
class JdbcReadSession implements ReadSession {
    protected final Connection connection;
    protected final SqlDialect dialect;

    JdbcReadSession(Connection connection, SqlDialect dialect) {
        this.connection = connection;
        this.dialect = dialect;
    }

    @Override
    public Optional<Map<String, Object>> findById(CollectionDefinition schema, Object id) {
        String sql = "SELECT * FROM " + dialect.quote(schema.name()) + " WHERE "
                + dialect.quote(schema.idField()) + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcValues.bind(statement, new Object[]{id},
                    new org.broken.arrow.library.database.schema.FieldType[]{schema.field(schema.idField()).type()});
            statement.setMaxRows(1);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(JdbcValues.read(results, schema)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw JdbcProvider.wrapSql("Read by primary key", failure, false);
        }
    }

    @Override
    public List<Map<String, Object>> find(CollectionDefinition schema, RecordQuery query) {
        SqlQuery.Compiled compiled = SqlQuery.compile(dialect, schema, query, false);
        try (PreparedStatement statement = connection.prepareStatement(compiled.sql())) {
            JdbcValues.bind(statement, compiled.parameters(), compiled.types());
            statement.setMaxRows(query.limit());
            try (ResultSet results = statement.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                while (results.next()) rows.add(JdbcValues.read(results, schema));
                return List.copyOf(rows);
            }
        } catch (SQLException failure) {
            throw JdbcProvider.wrapSql("Bounded query", failure, false);
        }
    }

    /** Calculates a bounded-filter SUM directly in SQL and fails on long overflow. */
    @Override
    public long sumLong(CollectionDefinition schema, RecordQuery query, String field) {
        requireLong(schema, field);
        SqlQuery.Predicate predicate = SqlQuery.predicate(dialect, schema, query);
        String sql = "SELECT SUM(" + dialect.quote(field) + ") FROM "
                + dialect.quote(schema.name()) + predicate.sql();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcValues.bind(statement, predicate.parameters(), predicate.types());
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) return 0L;
                java.math.BigDecimal sum = results.getBigDecimal(1);
                return sum == null ? 0L : sum.longValueExact();
            }
        } catch (SQLException failure) {
            throw JdbcProvider.wrapSql("SUM aggregation", failure, false);
        }
    }

    /** Returns a bounded SQL GROUP BY result without transferring full source rows. */
    @Override
    public List<org.broken.arrow.library.database.repository.GroupedLongSum> groupSumLong(
            CollectionDefinition schema, RecordQuery query, String groupField, String sumField, int limit) {
        requireLong(schema, sumField);
        FieldType groupType = schema.field(groupField).type();
        if (groupType == FieldType.JSON || groupType == FieldType.BYTES
                || schema.field(groupField).nullable()) {
            throw new IllegalArgumentException("Group field must be non-null scalar: " + groupField);
        }
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("Group limit must be 1..1000");
        SqlQuery.Predicate predicate = SqlQuery.predicate(dialect, schema, query);
        String key = dialect.quote(groupField);
        String sql = "SELECT " + key + ", SUM(" + dialect.quote(sumField) + ") AS "
                + dialect.quote("total") + " FROM " + dialect.quote(schema.name())
                + predicate.sql() + " GROUP BY " + key + " ORDER BY " + dialect.quote("total")
                + " DESC, " + key + " ASC LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            Object[] params = java.util.Arrays.copyOf(predicate.parameters(), predicate.parameters().length + 1);
            params[params.length - 1] = limit;
            FieldType[] types = java.util.Arrays.copyOf(predicate.types(), predicate.types().length + 1);
            types[types.length - 1] = FieldType.INTEGER;
            JdbcValues.bind(statement, params, types);
            List<org.broken.arrow.library.database.repository.GroupedLongSum> groups = new ArrayList<>();
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    Object keyValue = switch (groupType) {
                        case UUID -> java.util.UUID.fromString(results.getString(1));
                        case INSTANT -> java.time.Instant.ofEpochMilli(results.getLong(1));
                        case INTEGER -> results.getInt(1);
                        case LONG -> results.getLong(1);
                        case STRING -> results.getString(1);
                        case BOOLEAN -> results.getBoolean(1);
                        case DOUBLE -> results.getDouble(1);
                        case DECIMAL -> results.getBigDecimal(1);
                        case JSON, BYTES -> throw new IllegalStateException("Invalid group type");
                    };
                    java.math.BigDecimal total = results.getBigDecimal(2);
                    groups.add(new org.broken.arrow.library.database.repository.GroupedLongSum(
                            keyValue, total == null ? 0L : total.longValueExact()));
                }
            }
            return List.copyOf(groups);
        } catch (SQLException failure) {
            throw JdbcProvider.wrapSql("GROUP SUM aggregation", failure, false);
        }
    }

    private static void requireLong(CollectionDefinition schema, String field) {
        if (schema.field(field).type() != FieldType.LONG) {
            throw new IllegalArgumentException("SUM requires a LONG database field: " + field);
        }
    }

    @Override
    public long count(CollectionDefinition schema, RecordQuery query) {
        SqlQuery.Compiled compiled = SqlQuery.compile(dialect, schema, query, true);
        try (PreparedStatement statement = connection.prepareStatement(compiled.sql())) {
            JdbcValues.bind(statement, compiled.parameters(), compiled.types());
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getLong(1) : 0L;
            }
        } catch (SQLException failure) {
            throw JdbcProvider.wrapSql("Count query", failure, false);
        }
    }
}
