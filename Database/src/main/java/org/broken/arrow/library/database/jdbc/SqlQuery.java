package org.broken.arrow.library.database.jdbc;

import java.util.ArrayList;
import java.util.List;

import org.broken.arrow.library.database.repository.RecordQuery;

import org.broken.arrow.library.database.schema.CollectionDefinition;
import org.broken.arrow.library.database.schema.FieldType;

/** Safe SQL compiler for the portable immutable query predicate subset. */
final class SqlQuery {
    record Compiled(String sql, Object[] parameters, FieldType[] types) { }

    /** Compiles SELECT or COUNT with bound parameters and known columns only. */
    static Compiled compile(SqlDialect dialect, CollectionDefinition schema, RecordQuery query, boolean count) {
        query.validate(schema);
        StringBuilder sql = new StringBuilder(count ? "SELECT COUNT(*) FROM " : "SELECT * FROM ")
                .append(dialect.quote(schema.name()));
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
        if (!count) {
            sql.append(" ORDER BY ").append(dialect.quote(query.orderBy() == null
                    ? schema.idField() : query.orderBy()))
                    .append(query.descending() ? " DESC" : " ASC");
            sql.append(" LIMIT ? OFFSET ?");
            parameters.add(query.limit());
            parameters.add(query.offset());
            types.add(FieldType.INTEGER);
            types.add(FieldType.INTEGER);
        }
        return new Compiled(sql.toString(), parameters.toArray(), types.toArray(FieldType[]::new));
    }

    private SqlQuery() { }
}
