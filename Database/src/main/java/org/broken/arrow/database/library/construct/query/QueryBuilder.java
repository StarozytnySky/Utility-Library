package org.broken.arrow.database.library.construct.query;


import org.broken.arrow.database.library.construct.query.builder.CreateTableHandler;
import org.broken.arrow.database.library.construct.query.builder.InsertHandler;
import org.broken.arrow.database.library.construct.query.builder.QueryRemover;
import org.broken.arrow.database.library.construct.query.builder.UpdateBuilder;
import org.broken.arrow.database.library.construct.query.builder.WithManger;
import org.broken.arrow.database.library.construct.query.builder.insertbuilder.InsertBuilder;
import org.broken.arrow.database.library.construct.query.columnbuilder.Column;
import org.broken.arrow.database.library.construct.query.columnbuilder.ColumnManger;
import org.broken.arrow.database.library.construct.query.utlity.QueryType;
import org.broken.arrow.database.library.construct.query.utlity.StringUtil;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class QueryBuilder {
    private final UpdateBuilder updateBuilder = new UpdateBuilder();
    private final InsertHandler insertHandler = new InsertHandler();
    private final QueryModifier queryModifier = new QueryModifier();
    private final CreateTableHandler createTableHandler = new CreateTableHandler();
    private final QueryRemover queryRemover = new QueryRemover();
    private final WithManger withManger = new WithManger();
    private QueryType queryType;
    private String table;
    private boolean globalEnableQueryPlaceholders = true;

    public CreateTableHandler createTable(String table) {
        this.queryType = QueryType.CREATE;
        this.table = table;
        return createTableHandler;
    }

    public CreateTableHandler createTableIfNotExists(String table) {
        this.queryType = QueryType.CREATE_IF_NOT_EXISTS;
        this.table = table;
        return createTableHandler;
    }

    public CreateTableHandler dropTable(String table) {
        this.queryType = QueryType.DROP;
        this.table = table;
        return createTableHandler;
    }

    public QueryModifier select() {
        this.queryType = QueryType.SELECT;
        return queryModifier;
    }

    public QueryModifier select(ColumnManger column) {
        this.queryType = QueryType.SELECT;
        queryModifier.select(selectBuilder -> selectBuilder.addAll(column.getColumnsBuilt()));
        return queryModifier;
    }

    public QueryModifier select(List<Column> column) {
        this.queryType = QueryType.SELECT;
        queryModifier.select(selectBuilder -> selectBuilder.addAll(column));
        return queryModifier;
    }

    public UpdateBuilder update(String table, Consumer<UpdateBuilder> callback) {
        callback.accept(updateBuilder);
        this.queryType = QueryType.UPDATE;
        this.table = table;
        return updateBuilder;
    }

    public UpdateBuilder update(String table) {
        this.queryType = QueryType.UPDATE;
        this.table = table;
        return this.updateBuilder;
    }

    public void insertInto(String table, Consumer<InsertHandler> callback) {
        callback.accept(insertHandler);
        this.queryType = QueryType.INSERT;
        this.table = table;
    }

    public void mergeInto(String table, Consumer<InsertHandler> callback) {
        callback.accept(insertHandler);
        this.queryType = QueryType.MERGE_INTO;
        this.table = table;
    }

    public void replaceInto(String table, Consumer<InsertHandler> callback) {
        callback.accept(insertHandler);
        this.queryType = QueryType.REPLACE_INTO;
        this.table = table;
    }


    public QueryRemover deleteFrom(String table) {
        this.queryType = QueryType.DELETE;
        this.table = table;
        return queryRemover;
    }

    public WithManger with(Consumer<WithManger> callback) {
        this.queryType = QueryType.WITH;
        callback.accept(withManger);
        return withManger;
    }

    public boolean isGlobalEnableQueryPlaceholders() {
        return globalEnableQueryPlaceholders;
    }

    public QueryBuilder setGlobalEnableQueryPlaceholders(final boolean globalEnableQueryPlaceholders) {
        this.globalEnableQueryPlaceholders = globalEnableQueryPlaceholders;
        return this;
    }

    public String getTableName() {
        return table;
    }

    // ---- Query Build ----
    public String build() {
        final QueryModifier queryModifier = this.queryModifier;
        if (queryType == null) {
            throw new IllegalStateException("Query type must be set before building.");
        }

        StringBuilder sql = new StringBuilder();
        switch (queryType) {
            case SELECT:
                sql.append("SELECT ");

                sql.append(queryModifier.getSelectBuilder().getColumns().isEmpty() ? "*" : queryModifier.getSelectBuilder().build());

                sql.append(" FROM ").append(queryModifier.getTableWithAlias())
                        .append(queryModifier.getJoinBuilder().build())
                        .append(queryModifier.getWhereBuilder().build())
                        .append(queryModifier.getGroupByBuilder().build())
                        .append(queryModifier.getHavingBuilder().build())
                        .append(queryModifier.getOrderByBuilder().build());
                break;
            case DELETE:
                sql.append("DELETE FROM ").append(table);
                sql.append(this.queryRemover.getWhereBuilder().build());
                break;

            case DROP:
                sql.append("DROP TABLE ").append(table);
                break;

            case CREATE:
                sql.append("CREATE TABLE ").append(table).append(this.createTableHandler.build());
                break;

            case CREATE_IF_NOT_EXISTS:
                sql.append("CREATE TABLE IF NOT EXISTS ").append(table).append(this.createTableHandler.build());
                break;
            case UPDATE:
                Map<String, Object> updateValues = updateBuilder.build();
                if (updateValues.isEmpty()) {
                    throw new IllegalStateException("UPDATE queries require at least one SET value.");
                }
                sql.append("UPDATE ").append(table).append(" SET ");
                if (this.globalEnableQueryPlaceholders) {
                    sql.append(updateValues.entrySet().stream()
                            .map(entry -> entry.getKey() + " = ?")
                            .collect(Collectors.joining(", ")));
                } else {
                    sql.append(updateValues.entrySet().stream()
                            .map(entry -> entry.getKey() + " = " + entry.getValue())
                            .collect(Collectors.joining(", ")));
                }
                sql.append(updateBuilder.getSelector().getWhereBuilder().build());
                break;

            case INSERT:
            case MERGE_INTO:
            case REPLACE_INTO:
                String sqlKeyword = getInsertStart();
                Set<Map.Entry<Integer, InsertBuilder>> insertValues = insertHandler.getInsertValues().entrySet();

                List<InsertBuilder> insertBuilders = insertValues.stream()
                        .sorted(Comparator.comparingInt(Map.Entry::getKey))
                        .map(Map.Entry::getValue)
                        .collect(Collectors.toList());

                List<String> columnNames = insertBuilders.stream()
                        .map(InsertBuilder::getColumnName)
                        .collect(Collectors.toList());

                sql.append(sqlKeyword).append(table).append(" (")
                        .append(StringUtil.stringJoin(columnNames))
                        .append(") VALUES (");

                if (this.globalEnableQueryPlaceholders) {
                    sql.append(StringUtil.repeat("?,", insertBuilders.size()).replaceAll(",$", ""));
                } else {
                    List<Object> columnValues = insertBuilders.stream()
                            .map(InsertBuilder::getColumnValue)
                            .collect(Collectors.toList());
                    sql.append(StringUtil.stringJoin(columnValues));
                }
                sql.append(")");
                break;
            case WITH:
                sql.append(withManger.build());
                break;
        }
        return sql + ";";
    }

    @Nonnull
    private String getInsertStart() {
        String sqlKeyword;
        switch (queryType) {
            case INSERT:
                sqlKeyword = "INSERT INTO ";
                break;
            case MERGE_INTO:
                sqlKeyword = "MERGE INTO ";
                break;
            case REPLACE_INTO:
                sqlKeyword = "REPLACE INTO ";
                break;
            default:
                sqlKeyword = "";
        }
        return sqlKeyword;
    }

    public Map<Integer, Object> getValues() {

        List<Object> values = new ArrayList<>();
        if (queryType == QueryType.UPDATE) {
            return updateBuilder.getIndexedValues();
        } else if (queryType == QueryType.INSERT || queryType == QueryType.MERGE_INTO || queryType == QueryType.REPLACE_INTO) {
            return insertHandler.getIndexedValues();
        } else if (queryType == QueryType.SELECT) {
            if (!queryModifier.getWhereBuilder().getValues().isEmpty()) {
                return queryModifier.getWhereBuilder().getValues();
            } else {
                return queryModifier.getHavingBuilder().getValues();
            }
        } else if (queryType == QueryType.DELETE) {
            return queryRemover.getWhereBuilder().getValues();
        } else if (queryType == QueryType.WITH) {

        }
        return new HashMap<>();
    }

    public int getAmountColumnsSet() {

        if (queryType == QueryType.UPDATE) {
            return updateBuilder.getSelector().getSelectBuilder().getColumns().size();
        } else if (queryType == QueryType.INSERT) {
            return insertHandler.getInsertValues().size();
        } else if (queryType == QueryType.SELECT) {
            return queryModifier.getSelectBuilder().getColumns().size();
        } else if (queryType == QueryType.DELETE) {
            return -1;
        } else {
        }
        return -1;
    }


}