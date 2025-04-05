package org.broken.arrow.database.library.construct.query.builder.tablebuilder;

import org.broken.arrow.database.library.construct.query.builder.CreateTableHandler;

public class BuildTable extends SelectorWrapper {
    private final CreateTableHandler createTableHandler;
    public BuildTable(CreateTableHandler createTableHandler) {
        super(createTableHandler);
        this.createTableHandler = createTableHandler;
    }

    public CreateTableHandler build() {
        return createTableHandler;
    }
}