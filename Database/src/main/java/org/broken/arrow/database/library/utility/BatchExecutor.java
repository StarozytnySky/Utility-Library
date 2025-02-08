package org.broken.arrow.database.library.utility;

import org.broken.arrow.database.library.Database;
import org.broken.arrow.database.library.builders.DataWrapper;
import org.broken.arrow.database.library.builders.RowDataWrapper;
import org.broken.arrow.database.library.builders.RowWrapper;
import org.broken.arrow.database.library.builders.SqlQueryBuilder;
import org.broken.arrow.database.library.builders.tables.SqlCommandComposer;
import org.broken.arrow.database.library.builders.tables.TableRow;
import org.broken.arrow.database.library.builders.tables.TableWrapper;
import org.broken.arrow.logging.library.Logging;
import org.broken.arrow.logging.library.Validate;
import org.broken.arrow.serialize.library.utility.serialize.ConfigurationSerializable;

import javax.annotation.Nonnull;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;

import static org.broken.arrow.logging.library.Logging.of;

public class BatchExecutor {
    private final Logging log = new Logging(BatchExecutor.class);
    protected final Database database;
    private final DatabaseCommandConfig databaseConfig;
    protected final List<DataWrapper> dataWrapperList;
    protected final Connection connection;
    protected final int resultSetType;
    protected final int resultSetConcurrency;
    private volatile boolean batchUpdateGoingOn;

    public BatchExecutor(@Nonnull final Database database, @Nonnull final Connection connection, @Nonnull final List<DataWrapper> dataWrapperList) {
        this.database = database;
        this.connection = connection;
        this.dataWrapperList = dataWrapperList;
        this.databaseConfig = this.database.databaseConfig();
        this.resultSetType = this.databaseConfig.getResultSetType();
        this.resultSetConcurrency = this.databaseConfig.getResultSetConcurrency();
    }

    public void saveAll(@Nonnull final String tableName, final boolean shallUpdate, String... columns) {
        final List<SqlCommandComposer> composerList = new ArrayList<>();

        final TableWrapper tableWrapper = this.database.getTable(tableName);
        if (tableWrapper == null) {
            this.printFailFindTable(tableName);
            return;
        }

        for (DataWrapper dataWrapper : dataWrapperList) {
            TableRow primaryRow = tableWrapper.getPrimaryRow();
            if (dataWrapper == null || primaryRow == null) continue;

            this.formatData(composerList, dataWrapper, shallUpdate, columns, tableWrapper);
        }
        executeDatabaseTask(composerList);
    }

    public void save(String tableName, @Nonnull final DataWrapper dataWrapper, boolean shallUpdate, String... columns) {
        final List<SqlCommandComposer> composerList = new ArrayList<>();
        TableWrapper tableWrapper = this.database.getTable(tableName);
        if (tableWrapper == null) {
            this.printFailFindTable(tableName);
            return;
        }
        if (!checkIfNotNull(dataWrapper)) return;

        this.formatData(composerList, dataWrapper, shallUpdate, columns, tableWrapper);

        executeDatabaseTask(composerList);
    }

    public void removeAll(String tableName, final List<String> values) {
        TableWrapper tableWrapper = this.database.getTable(tableName);
        if (tableWrapper == null) {
            this.printFailFindTable(tableName);
            return;
        }
        List<SqlCommandComposer> columns = new ArrayList<>();
        for (String value : values) {
            final SqlCommandComposer sqlCommandComposer = new SqlCommandComposer(new RowWrapper(tableWrapper),  this.database);
            sqlCommandComposer.removeRow(value);
            columns.add(sqlCommandComposer);
        }
        this.executeDatabaseTask(columns);
    }

    public void remove(String tableName, String value) {
        TableWrapper tableWrapper = this.database.getTable(tableName);
        if (tableWrapper == null) {
            this.printFailFindTable(tableName);
            return;
        }
        final SqlCommandComposer sqlCommandComposer = new SqlCommandComposer(new RowWrapper(tableWrapper),  this.database);
        sqlCommandComposer.removeRow(value);
        this.executeDatabaseTask(Collections.singletonList(sqlCommandComposer));
    }

    public void dropTable(String tableName) {
        TableWrapper tableWrapper = this.database.getTable(tableName);
        if (tableWrapper == null) {
            this.printFailFindTable(tableName);
            return;
        }
        final SqlCommandComposer sqlCommandComposer = new SqlCommandComposer(new RowWrapper(tableWrapper), this.database);
        sqlCommandComposer.dropTable();
        this.executeDatabaseTask(Collections.singletonList(sqlCommandComposer));
    }

    public void runSQLCommand(@Nonnull final SqlQueryBuilder... sqlQueryBuilders) {
        if (!checkIfNotNull(sqlQueryBuilders)) return;

        List<SqlCommandComposer> sqlComposer = new ArrayList<>();
        for (SqlQueryBuilder command : sqlQueryBuilders) {
            TableWrapper tableWrapper = TableWrapper.of(command, TableRow.of("", ""));
            final SqlCommandComposer sqlCommandComposer = new SqlCommandComposer(new RowWrapper(tableWrapper), this.database);
            sqlCommandComposer.executeCustomCommand();
            sqlComposer.add(sqlCommandComposer);
        }
        this.executeDatabaseTask(sqlComposer);
    }

    /**
     * Checks if a row exists in the specified table.
     * <p>&nbsp;</p>
     * <p>For save operations, running this check beforehand is unnecessary since the appropriate SQL
     * command will be used based on whether the value already exists.</p>
     *
     * @param tableName the name of the table to search for the data.
     * @param primaryKeyValue the primary key value to look for in the table.
     * @return {@code true} if the key exists in the table, or {@code false} if the data is not found
     *         or a connection issue occurs.
     */
    public boolean checkIfRowExist(@Nonnull String tableName, @Nonnull Object primaryKeyValue) {
        return this.checkIfRowExist(tableName, primaryKeyValue, true);
    }

    private void formatData(@Nonnull final List<SqlCommandComposer> composerList, @Nonnull final DataWrapper dataWrapper, final boolean shallUpdate, final String[] columns, final TableWrapper tableWrapper) {
        final ConfigurationSerializable configuration = dataWrapper.getConfigurationSerialize();
        final RowWrapper rowWrapper = new RowDataWrapper(tableWrapper, dataWrapper.getPrimaryValue());

        for (Map.Entry<String, Object> entry : configuration.serialize().entrySet()) {
            TableRow column = tableWrapper.getColumn(entry.getKey());
            if (column == null) continue;
            rowWrapper.putColumn(entry.getKey(), entry.getValue());
        }

        composerList.add(this.getCommandComposer(rowWrapper, shallUpdate, columns));
    }


    /**
     * Retrieves a SqlCommandComposer instance.
     *
     * @param rowWrapper  The current row's column data.
     * @param shallUpdate Specifies whether the table should be updated.
     * @param columns     If not null and not empty, updates the existing row with these columns if it exists.
     * @return The SqlCommandComposer instance with the finish SQL command for either use for prepare V or not.
     */
    private SqlCommandComposer getCommandComposer(@Nonnull final RowWrapper rowWrapper, final boolean shallUpdate, String... columns) {
        SqlCommandComposer commandComposer = new SqlCommandComposer(rowWrapper, this.database);
        commandComposer.setColumnsToUpdate(columns);

        boolean columnsIsEmpty = columns == null || columns.length == 0;
        boolean canUpdateRow = (!columnsIsEmpty || shallUpdate) && this.checkIfRowExist(rowWrapper.getTableWrapper().getTableName(), rowWrapper.getPrimaryKeyValue(),false);
        this.databaseConfig.applyDatabaseCommand(commandComposer, rowWrapper.getPrimaryKeyValue(), canUpdateRow);
        return commandComposer;
    }

    /**
     * Checks if a row exists in the specified table.
     * <p>&nbsp;<p>
     * <p><strong>Important:</strong> If you use this method, you must either:</p>
     * <ul>
     *   <li>Set {@code closeConnection} to {@code true} to automatically close the connection.</li>
     *   <li>Manually close the connection after use if {@code closeConnection} is set to {@code false}.</li>
     * </ul>
     *
     * <p>For save operations, running this check beforehand is unnecessary, as the appropriate SQL
     * command will be used based on whether the value already exists.</p>
     *
     * @param tableName the name of the table to search for the data.
     * @param primaryKeyValue the primary key value to look for in the table.
     * @param closeConnection set to {@code true} to close the connection after the call,
     *                        or {@code false} if you plan to use it further.
     * @return {@code true} if the key exists in the table, or {@code false} if either a connection
     *         issue occurs or the data is not found.
     */
    private boolean checkIfRowExist(@Nonnull String tableName, @Nonnull Object primaryKeyValue,boolean closeConnection) {
        TableWrapper tableWrapper = this.database.getTable(tableName);
        Connection connection = this.connection;
        if (tableWrapper == null) {
            this.printFailFindTable(tableName);
            return false;
        }
        Validate.checkNotNull(tableWrapper.getPrimaryRow(), "Could not find  primary column for table " + tableName);
        String primaryColumn = tableWrapper.getPrimaryRow().getColumnName();
        Validate.checkNotNull(primaryKeyValue, "Could not find column for " + primaryColumn + ". Because the column value is null.");

        final SqlCommandComposer sqlCommandComposer = new SqlCommandComposer(new RowWrapper(tableWrapper), this.database);
        try (PreparedStatement preparedStatement = connection.prepareStatement(sqlCommandComposer.selectRow(primaryKeyValue + ""))) {
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException e) {
            log.log(e, () -> of("Could not search for your the row with this value '" + primaryKeyValue + "' from this table '" + tableName + "'"));
        }
        if(closeConnection){
            try {
                connection.close();
            } catch (SQLException e) {
                log.log(Level.WARNING, e, () -> of("Failed to close database connection."));
            }
        }

        return false;
    }

    protected void executeDatabaseTask(List<SqlCommandComposer> composerList) {
        batchUpdateGoingOn = true;
        Connection connection = this.connection;
        final int processedCount = composerList.size();
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (batchUpdateGoingOn) log.log(() -> of("Still executing, DO NOT SHUTDOWN YOUR SERVER."));
                else cancel();
            }
        }, 1000 * 30L, 1000 * 30L);
        if (processedCount > 10_000)
            this.printPressesCount(processedCount);
        try {
            connection.setAutoCommit(false);
            int batchSize = 1;
            for (SqlCommandComposer sql : composerList) {
                this.setPreparedStatement(sql);

                if (batchSize % 100 == 0)
                    connection.commit();
                batchSize++;
            }
            connection.commit();
        } catch (SQLException e) {
            log.log(Level.WARNING, e, () -> of("Error during batch execution. Rolling back changes."));
            try {
                connection.rollback();
            } catch (SQLException rollbackEx) {
                log.log(Level.SEVERE, rollbackEx, () -> of("Failed to rollback changes after error."));
            }
            this.batchUpdateGoingOn = false;
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ex) {
                log.log(Level.WARNING, ex, () -> of("Could not reset auto-commit to true."));
            }
            try {
                connection.close();
            } catch (SQLException e) {
                log.log(Level.WARNING, e, () -> of("Failed to close database connection."));
            } finally {
                this.batchUpdateGoingOn = false;
                timer.cancel();
            }
        }
    }

    private void setPreparedStatement(SqlCommandComposer sql) throws SQLException {

        Map<Integer, Object> cachedDataByColumn = sql.getCachedDataByColumn();
        try (PreparedStatement statement = connection.prepareStatement(sql.getPreparedSQLBatch(), resultSetType, resultSetConcurrency)) {
            boolean valuesSet = false;

            if (!cachedDataByColumn.isEmpty()) {
                for (Map.Entry<Integer, Object> column : cachedDataByColumn.entrySet()) {
                    statement.setObject(column.getKey(), column.getValue());
                    valuesSet = true;
                }
            }
            if (valuesSet)
                statement.addBatch();
            statement.executeBatch();
        } catch (SQLException e) {
            log.log(Level.WARNING, () -> of("Could not execute this prepared batch: \"" + sql.getPreparedSQLBatch() + "\""));
            log.log(e, () -> of("Values that could not be executed: '" + cachedDataByColumn.values() + "'"));
        }
    }

    public void printPressesCount(int processedCount) {
        if (processedCount > 10_000)
            log.log(() -> of(("Updating your database (" + processedCount + " entries)... PLEASE BE PATIENT THIS WILL TAKE " + (processedCount > 50_000 ? "10-20 MINUTES" : "5-10 MINUTES") + " - If server will print a crash report, ignore it, update will proceed.")));
    }

    public boolean checkIfNotNull(Object object) {
        return object != null;
    }

    public void printFailFindTable(String tableName) {
        log.log(Level.WARNING, () -> of("Could not find table " + tableName));
    }
}
