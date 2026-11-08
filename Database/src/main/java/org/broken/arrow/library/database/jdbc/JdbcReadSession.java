package org.broken.arrow.library.database.jdbc;

import org.broken.arrow.library.database.api.CollectionDefinition;
import org.broken.arrow.library.database.api.ReadSession;
import org.broken.arrow.library.database.api.RecordQuery;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
                    new org.broken.arrow.library.database.api.FieldType[]{schema.field(schema.idField()).type()});
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
