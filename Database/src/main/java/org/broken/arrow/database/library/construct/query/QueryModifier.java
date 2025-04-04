package org.broken.arrow.database.library.construct.query;


import org.broken.arrow.database.library.construct.query.builder.GroupByBuilder;
import org.broken.arrow.database.library.construct.query.builder.JoinBuilder;
import org.broken.arrow.database.library.construct.query.builder.OrderByBuilder;
import org.broken.arrow.database.library.construct.query.builder.havingbuilder.HavingBuilder;
import org.broken.arrow.database.library.construct.query.builder.wherebuilder.WhereBuilder;
import org.broken.arrow.database.library.construct.query.columnbuilder.Column;
import org.broken.arrow.database.library.construct.query.columnbuilder.ColumnBuilder;

import java.util.function.Consumer;

public class QueryModifier extends Selector<ColumnBuilder<Column,Void>> {
  private final GroupByBuilder groupByBuilder = new GroupByBuilder();

  private final OrderByBuilder orderByBuilder = new OrderByBuilder();
  private int limit;

  public QueryModifier() {
    super(new ColumnBuilder<>());
  }

  @Override
  public QueryModifier from(String table) {
    super.from(table);
    return this;
  }

  @Override
  public QueryModifier from(String table, String alias) {
    super.from(table, alias);
    return this;
  }
  @Override
  public QueryModifier from(QueryBuilder queryBuilder) {
    super.from(queryBuilder);
    return this;
  }
  @Override
  public QueryModifier where(WhereBuilder whereBuilder) {
    super.where(whereBuilder);
    return this;
  }

  @Override
  public QueryModifier select(Consumer<ColumnBuilder<Column,Void>> callback) {
    super.select(callback);
    return this;
  }

  @Override
  public QueryModifier join(Consumer<JoinBuilder> callback) {
    super.join(callback);
    return this;
  }

  @Override
  public QueryModifier having(Consumer<HavingBuilder> callback) {
    super.having(callback);
    return this;
  }


  public QueryModifier groupBy(String... columns) {
    groupByBuilder.groupBy(columns);
    return this;
  }

  public QueryModifier orderBy(Consumer<OrderByBuilder> callback) {
    callback.accept(orderByBuilder);
    return this;
  }

  public QueryModifier limit(int limit) {
    this.limit = limit;
    return this;
  }

  public String getLimit() {
    return "LIMIT" + limit;
  }

  public GroupByBuilder getGroupByBuilder() {
    return groupByBuilder;
  }

  public OrderByBuilder getOrderByBuilder() {
    return orderByBuilder;
  }

}