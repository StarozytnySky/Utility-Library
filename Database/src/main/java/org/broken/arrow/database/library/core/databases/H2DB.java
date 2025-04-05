package org.broken.arrow.database.library.core.databases;

import org.broken.arrow.database.library.builders.ConnectionSettings;
import org.broken.arrow.database.library.connection.HikariCP;
import org.broken.arrow.database.library.core.SQLDatabaseQuery;
import org.broken.arrow.database.library.utility.DatabaseCommandConfig;
import org.broken.arrow.logging.library.Logging;

import javax.annotation.Nonnull;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.broken.arrow.logging.library.Logging.of;

public class H2DB extends SQLDatabaseQuery {
    private final Logging log = new Logging(H2DB.class);

    private final boolean isHikariAvailable;
    private final File dbFile;
    private HikariCP hikari;
    private boolean hasCastException;

    public H2DB(@Nonnull final String parent, @Nonnull final String child) {
        this("com.zaxxer.hikari.HikariConfig", parent, child);
    }

    public H2DB(@Nonnull final String hikariClazzPath, @Nonnull final String parent, @Nonnull final String child) {
        this(hikariClazzPath, new DBPath(parent, child));
    }

    public H2DB(String hikariClazzPath, DBPath dbPath) {
        super(new ConnectionSettings(dbPath.getDbFile().getPath()));
        this.dbFile = dbPath.getDbFile();

        this.isHikariAvailable = isHikariAvailable(hikariClazzPath);
        this.loadDriver("org.h2.Driver");
        connect();
    }


    @Override
    public Connection connect() {
        try {
            return setupConnection();
        } catch (SQLException e) {
            this.hasCastException = true;
            log.log(e, () -> of("Fail to connect to H2 database. With the file path: " + this.dbFile));
        }
        return null;
    }

    @Override
    public boolean usingHikari() {
        return this.isHikariAvailable;
    }


    @Nonnull
    @Override
    public DatabaseCommandConfig databaseConfig() {
        new DatabaseCommandConfig(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, (commandComposer, primaryKeyValue, rowExist) -> {
            if (rowExist)
                commandComposer.updateTable(primaryKeyValue);
            else
                commandComposer.mergeIntoTable();
        });
        return new DatabaseCommandConfig(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, (sqlHandler, columnData, whereClause, rowExist) -> {
            if (rowExist)
                return sqlHandler.updateTable(updateBuilder -> updateBuilder.putAll(columnData), whereClause);
            else
                return sqlHandler.insertIntoTable(insertHandler -> insertHandler.addAll(columnData));
        });
    }

/*    @Override
    protected SqlCommandComposer getCommandComposer(@Nonnull final RowWrapper rowWrapper, final boolean shallUpdate, String... columns) {
        SqlCommandComposer sqlCommandComposer = new SqlCommandComposer(rowWrapper, this);
        boolean columnsIsEmpty = columns == null || columns.length == 0;
        sqlCommandComposer.setColumnsToUpdate(columns);

        if ((!columnsIsEmpty || shallUpdate) && this.doRowExist(rowWrapper.getTableWrapper().getTableName(), rowWrapper.getPrimaryKeyValue()))
            sqlCommandComposer.updateTable(rowWrapper.getPrimaryKeyValue());
        else
            sqlCommandComposer.mergeIntoTable();
        return sqlCommandComposer;
    }*/

    public Connection setupConnection() throws SQLException {
        Connection connection;

        if (this.isHikariAvailable) {
            if (this.hikari == null) hikari = new HikariCP(this, "org.h2.Driver");
            connection = this.hikari.getFileConnection("jdbc:h2:");
        } else {
            connection = DriverManager.getConnection("jdbc:h2:" + this.dbFile.getPath());
        }
        hasCastException = false;
        return connection;
    }

    @Override
    public boolean isHasCastException() {
        return this.hasCastException;
    }

    private static class DBPath {
        private final File dbFile;

        public DBPath(String parent, String child) {
            if (parent != null && child == null)
                dbFile = new File(parent);
            else
                dbFile = new File(parent, child);
        }

        public File getDbFile() {
            return dbFile;
        }
    }
}
