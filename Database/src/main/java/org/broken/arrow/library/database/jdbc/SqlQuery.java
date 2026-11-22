package org.broken.arrow.library.database.jdbc;

import java.util.ArrayList;
import java.util.List;

import org.broken.arrow.library.database.repository.RecordQuery;

import org.broken.arrow.library.database.schema.CollectionDefinition;
import org.broken.arrow.library.database.schema.FieldType;

/** Safe SQL compiler for the portable immutable query predicate subset. */
final class SqlQuery {
    record Compiled(String sql, Object[] parameters, FieldType[] types) { }

    /**
     * Compiles SELECT or COUNT with bound parameters and known columns only.
     *
     * @param dialect SQL dialect selected for the current backend
     * @param schema definition of the target table or collection
     * @param query validated selection predicate and query options
     * @param count maximum number of rows or records to process
     * @return computed result of the requested operation
     */
    static Compiled compile(SqlDialect dialect, CollectionDefinition schema, RecordQuery query, boolean count) {
        query.validate(schema);
        StringBuilder sql = new StringBuilder(count ? "SELECT COUNT(*) FROM " : "SELECT * FROM ")
                .append(dialect.quote(schema.name()));
        Predicate predicate = predicate(dialect, schema, query);
        sql.append(predicate.sql());
        List<Object> parameters = new ArrayList<>(java.util.Arrays.asList(predicate.parameters()));
        List<FieldType> types = new ArrayList<>(java.util.Arrays.asList(predicate.types()));
        if (!count) {
            sql.append(" ORDER BY ").append(dialect.quote(query.orderBy() == null
                            ? schema.idField() : query.orderBy()))
                    .append(query.descending() ? " DESC" : " ASC");
            for (String key : schema.idFields()) {
                if (key.equals(query.orderBy() == null ? schema.idField() : query.orderBy())) continue;
                sql.append(", ").append(dialect.quote(key))
                        .append(query.descending() ? " DESC" : " ASC");
            }
            sql.append(" LIMIT ? OFFSET ?");
            parameters.add(query.limit());
            parameters.add(query.offset());
            types.add(FieldType.INTEGER);
            types.add(FieldType.INTEGER);
        }
        return new Compiled(sql.toString(), parameters.toArray(), types.toArray(FieldType[]::new));
    }

    /**
     * Builds a primary-key predicate with one bound parameter for each key component.
     *
     * @param dialect SQL dialect responsible for quoting column names
     * @param schema collection definition containing ordered primary-key fields
     * @param id scalar or composite identifier to bind
     * @return parameterized predicate and its ordered argument types
     */
    static Predicate keyPredicate(SqlDialect dialect, CollectionDefinition schema, Object id) {
        var values = schema.keyValues(id);
        String where = " WHERE " + schema.idFields().stream()
                .map(field -> dialect.quote(field) + " = ?")
                .collect(java.util.stream.Collectors.joining(" AND "));
        var types = schema.idFields().stream().map(field -> schema.field(field).type())
                .toArray(FieldType[]::new);
        return new Predicate(where, values.toArray(), types);
    }

    /** An already validated, prepared WHERE clause reusable in SELECT and UPDATE statements. */
    record Predicate(String sql, Object[] parameters, FieldType[] types) { }

    /**
     * Compiles only filter conditions, never embedding application values in SQL.
     *
     * @param dialect SQL dialect selected for the current backend
     * @param schema definition of the target table or collection
     * @param query validated selection predicate and query options
     * @return computed result of the requested operation
     */
    static Predicate predicate(SqlDialect dialect, CollectionDefinition schema, RecordQuery query) {
        query.validate(schema);
        StringBuilder sql = new StringBuilder();
        List<Object> parameters = new ArrayList<>();
        List<FieldType> types = new ArrayList<>();
        if (!query.conditions().isEmpty()) sql.append(" WHERE ");
        for (int i = 0; i < query.conditions().size(); i++) {
            if (i != 0) sql.append(" AND ");
            RecordQuery.Condition condition = query.conditions().get(i);
            sql.append(dialect.quote(condition.field()));
            if (condition.value() == null) {
                sql.append(condition.operator() == RecordQuery.Operator.EQ ? " IS NULL" : " IS NOT NULL");
            } else {
                sql.append(switch (condition.operator()) {
                    case EQ -> " = ?";
                    case NE -> " <> ?";
                    case GT -> " > ?";
                    case GTE -> " >= ?";
                    case LT -> " < ?";
                    case LTE -> " <= ?";
                });
                parameters.add(condition.value());
                types.add(schema.field(condition.field()).type());
            }
        }
        return new Predicate(sql.toString(), parameters.toArray(), types.toArray(FieldType[]::new));
    }

    private SqlQuery() { }
}
