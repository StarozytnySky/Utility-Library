package org.broken.arrow.database.library.construct.query.builder.tablebuilder;

import org.broken.arrow.database.library.construct.query.builder.CreateTableHandler;
import org.broken.arrow.database.library.construct.query.builder.TableColumnCache;

import java.util.List;

public class TableSelectorWrapper {
    private final CreateTableHandler createTableHandler;
    private TableSelector selector;

    public TableSelectorWrapper(CreateTableHandler createTableHandler, TableSelector selector) {
      this.createTableHandler = createTableHandler;
      this.selector = selector;
    }

    public TableSelectorWrapper select(TableColumn columnsBuilder) {
      selector = new TableSelector(new TableColumnCache());
      selector.select(tablesColumns -> tablesColumns.add(columnsBuilder));
      return this;
    }

    public TableSelectorWrapper select(List<TableColumn> columnsBuilder) {
        selector = new TableSelector(new TableColumnCache());
        selector.select(tablesColumns -> tablesColumns.addAll(columnsBuilder));
        return this;
    }

    public TableSelector build() {
      return selector;
    }

}