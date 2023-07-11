package org.broken.arrow.database.library;

import org.broken.arrow.database.library.builders.DataWrapper;
import org.broken.arrow.database.library.builders.LoadDataWrapper;
import org.broken.arrow.database.library.builders.TableWrapper;
import org.broken.arrow.database.library.builders.tables.TableRow;
import org.broken.arrow.database.library.log.LogMsg;
import org.broken.arrow.database.library.log.Validate;
import org.broken.arrow.database.library.utility.DatabaseType;
import org.broken.arrow.database.library.utility.serialize.MethodReflectionUtils;
import org.broken.arrow.serialize.library.utility.serialize.ConfigurationSerializable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

public abstract class Database {

	protected Connection connection;
	private final MethodReflectionUtils methodReflectionUtils = new MethodReflectionUtils();
	protected boolean batchUpdateGoingOn = false;
	private final Map<String, TableWrapper> tables = new HashMap<>();
	protected boolean hasStartWriteToDb = false;
	private final DatabaseType databaseType;
	private Set<String> removeColumns = new HashSet<>();

	public Database() {
		if (this instanceof SQLite) {
			this.databaseType = DatabaseType.SQLite;
			return;
		}
		if (this instanceof MySQL) {
			this.databaseType = DatabaseType.MySQL;
			return;
		}
		if (this instanceof H2DB) {
			this.databaseType = DatabaseType.H2;
			return;
		}
		this.databaseType = DatabaseType.Unknown;
	}

	/**
	 * The conetion to the database.
	 *
	 * @return the connection.
	 */
	public abstract Connection connect();

	/**
	 * The batchUpdate method, override this method to self set the {@link #batchUpdate(java.util.List, int, int)} method.
	 *
	 * @param batchList     list of commands that should be executed.
	 * @param tableWrappers the table wrapper involved in the execution of this event.
	 */
	protected abstract void batchUpdate(@Nonnull final List<String> batchList, @Nonnull final TableWrapper... tableWrappers);

	/**
	 * Create all needed tables if it not exist.
	 */
	public void createTables() {
		Validate.checkBoolean(tables.isEmpty(), "The table is empty, add tables to the map before call this method");
		try {
			createAllTablesIfNotExist();
			try {
				for (final Entry<String, TableWrapper> entityTables : tables.entrySet()) {
					final List<String> columns = updateTableColumnsInDb(entityTables.getKey());
					this.createMissingColumns(entityTables.getValue(), columns);
				}
			} catch (final SQLException throwable) {
				throwable.printStackTrace();
			}
		} finally {
			closeConnection();
		}
	}

	/**
	 * Saves all rows to the specified database table, based on the provided primary key and associated data.
	 * Note: that this method can also use previously added records using either the {@link TableWrapper#addRecord(String)}
	 * or {@link TableWrapper#addAllRecord(Set)} methods and it will then update all rows you added. If only one
	 * record added it will update only one row. If no record is set, it will instead replace the old data.
	 *
	 * @param tableName       name of the table you want to update rows inside.
	 * @param dataWrapperList List of data you want to cache to database.
	 *                        Note: the key you set has to be the primary key you want to update.
	 */
	public void saveAll(@Nonnull final String tableName, @Nonnull final List<DataWrapper> dataWrapperList) {
		final List<String> sqls = new ArrayList<>();

		final TableWrapper tableWrapper = this.getTable(tableName);
		if (tableWrapper == null) {
			LogMsg.warn("Could not find table " + tableName);
			return;
		}
		if (!openConnection()) return;
		for (DataWrapper dataWrapper : dataWrapperList) {
			if (dataWrapper == null) continue;
			String primaryKey = dataWrapper.getPrimaryKey();

			if (!primaryKey.isEmpty()) {
				TableRow column = tableWrapper.getPrimaryRow();
				if (column != null) {
					tableWrapper.setPrimaryRow(column.getBuilder().setColumnValue(dataWrapper.getValue()).build());
				}
			}
			for (Entry<String, Object> entry : dataWrapper.getConfigurationSerialize().serialize().entrySet()) {
				TableRow column = tableWrapper.getColumn(entry.getKey());
				if (column == null) continue;
				tableWrapper.addCustom(entry.getKey(), column.getBuilder().setColumnValue(entry.getValue()));

			}
			this.getSqlsCommands(sqls, tableWrapper);
		}
		this.batchUpdate(sqls, this.getTables().values().toArray(new TableWrapper[0]));
	}

	/**
	 * Saves a single row to the specified database table, based on the provided primary key and associated data.
	 * Note: that this method can also use previously added records using either the {@link TableWrapper#addRecord(String)}
	 * or {@link TableWrapper#addAllRecord(Set)} methods and it will then update all rows you added. If not set or only one
	 * record added it will update only one row. If no record is set, it will instead replace the old data.
	 *
	 * @param tableName   The name of the table to save the row to.
	 * @param dataWrapper The wrapper with the set values, for primaryKey, primaryValue and serialize data.
	 */
	public void save(@Nonnull final String tableName, @Nonnull final DataWrapper dataWrapper) {
		final List<String> sqls = new ArrayList<>();
		TableWrapper tableWrapper = this.getTable(tableName);
		if (tableWrapper == null) {
			LogMsg.warn("Could not find table " + tableName);
			return;
		}
		if (dataWrapper == null)
			return;
		String primaryKey = dataWrapper.getPrimaryKey();
		Object primaryValue = dataWrapper.getValue();
		ConfigurationSerializable configuration = dataWrapper.getConfigurationSerialize();
		if (!primaryKey.isEmpty()) {
			TableRow primaryRow = tableWrapper.getPrimaryRow();
			if (primaryRow != null)
				tableWrapper.setPrimaryRow(primaryRow.getBuilder().setColumnValue(primaryValue).build());
		}
		for (Entry<String, Object> entry : configuration.serialize().entrySet()) {
			TableRow column = tableWrapper.getColumn(entry.getKey());
			if (column == null) continue;
			tableWrapper.addCustom(entry.getKey(), column.getBuilder().setColumnValue(entry.getValue()));
		}
		this.getSqlsCommands(sqls, tableWrapper);
		this.batchUpdate(sqls, tableWrapper);
	}

	/**
	 * Load all rows from specified database table.
	 *
	 * @param tableName name of the table you want to get data from.
	 * @param clazz     the class you have your static deserialize method.
	 * @param <T>       the type of ConfigurationSerialize instance.
	 * @return list of all data you have in the table.
	 */
	@Nullable
	public <T extends ConfigurationSerializable> List<LoadDataWrapper<T>> loadAll(@Nonnull final String tableName, @Nonnull final Class<T> clazz) {
		final List<LoadDataWrapper<T>> loadDataWrappers = new ArrayList<>();
		PreparedStatement preparedStatement = null;
		ResultSet resultSet = null;

		TableWrapper tableWrapper = this.getTable(tableName);
		if (tableWrapper == null) {
			LogMsg.warn("Could not find table " + tableName);
			return null;
		}
		if (!openConnection()) return null;
		Validate.checkNotNull(tableWrapper.getPrimaryRow(), "Primary column should not be null");
		try {
			preparedStatement = connection.prepareStatement(tableWrapper.selectTable());
			resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				Map<String, Object> dataFromDB = this.getDataFromDB(resultSet);
				T deserialize = this.methodReflectionUtils.invokeDeSerializeMethod(clazz, "deserialize", dataFromDB);
				loadDataWrappers.add(new LoadDataWrapper<>(tableWrapper.getPrimaryRow().getColumnName(), dataFromDB, deserialize));
			}

		} catch (SQLException e) {
			e.printStackTrace();
		} finally {
			this.close(preparedStatement, resultSet);
		}
		return loadDataWrappers;
	}

	/**
	 * Load one row from specified database table.
	 *
	 * @param tableName   name of the table you want to get data from.
	 * @param clazz       the class you have your static deserialize method.
	 * @param columnValue the value of the primary key you want to find data from.
	 * @param <T>         Type of class that extends ConfigurationSerializable .
	 * @return one row you have in the table.
	 */
	@Nullable
	public <T extends ConfigurationSerializable> LoadDataWrapper<T> load(@Nonnull final String tableName, @Nonnull final Class<T> clazz, String columnValue) {
		TableWrapper tableWrapper = this.getTable(tableName);
		if (!openConnection()) return null;
		if (tableWrapper == null) {
			LogMsg.warn("Could not find table " + tableName);
			return null;
		}
		Validate.checkNotNull(tableWrapper.getPrimaryRow(), "Could not find  primary column for table " + tableName);
		String primaryColumn = tableWrapper.getPrimaryRow().getColumnName();
		Map<String, Object> dataFromDB = new HashMap<>();
		Validate.checkNotNull(columnValue, "Could not find column for " + primaryColumn + ". Because the column value is null.");
		PreparedStatement preparedStatement = null;
		ResultSet resultSet = null;
		try {
			preparedStatement = this.connection.prepareStatement(tableWrapper.selectRow(columnValue));
			resultSet = preparedStatement.executeQuery();
			dataFromDB.putAll(this.getDataFromDB(resultSet));

		} catch (SQLException e) {
			e.printStackTrace();
		} finally {
			this.close(preparedStatement, resultSet);
		}
		T deserialize = this.methodReflectionUtils.invokeDeSerializeMethod(clazz, "deserialize", dataFromDB);
		return new LoadDataWrapper<>(tableWrapper.getPrimaryRow().getColumnName(), dataFromDB, deserialize);
	}

	/**
	 * Create all tables if it not exist yet, will only create a table if it not already exist.
	 */
	public void createAllTablesIfNotExist() {
		//if (!openConnection()) return;
		for (final Entry<String, TableWrapper> wrapperEntry : tables.entrySet()) {
			this.createTableIfNotExist(wrapperEntry.getKey());
		}
	}


	/**
	 * Create table if it not exist yet, will only create a table if it not already exist.
	 *
	 * @param tableName the name of the table to create.
	 * @return true if it could check if the table exist or create now table.
	 */
	public boolean createTableIfNotExist(final String tableName) {
		if (!openConnection()) return false;
		PreparedStatement statement = null;
		try {
			TableWrapper wrapperEntry = this.getTable(tableName);
			if (wrapperEntry == null) {
				LogMsg.warn("Could not find table " + tableName);
				return false;
			}
			statement = this.connection.prepareStatement(wrapperEntry.createTable());
			statement.executeUpdate();
			TableRow wrapper = wrapperEntry.getColumns().values().stream().findFirst().orElse(null);
			Validate.checkNotNull(wrapper, "Could not find a column for this table " + tableName);
			checkIfTableExist(tableName, wrapper.getColumnName());
			return true;
		} catch (final SQLException e) {
			e.printStackTrace();
			return false;
		} finally {
			close(statement);
		}
	}

	/**
	 * Remove all rows from specified database table.
	 *
	 * @param tableName name of the table you want to get data from.
	 * @param values    the list of primary key values you want to remove from database.
	 */
	public void removeAll(final String tableName, final List<String> values) {
		TableWrapper tableWrapper = this.getTable(tableName);
		if (tableWrapper == null) {
			LogMsg.warn("Could not find table " + tableName);
			return;
		}
		List<String> columns = new ArrayList<>();
		for (String value : values) {
			columns.add(tableWrapper.removeRow(value));
		}
		this.batchUpdate(columns, tableWrapper);
	}

	/**
	 * Remove one row from specified database table.
	 *
	 * @param tableName name of the table you want to get data from.
	 * @param value     the primary key value you want to remove from database.
	 */
	public void remove(final String tableName, final String value) {
		TableWrapper tableWrapper = this.getTable(tableName);
		if (tableWrapper == null) {
			LogMsg.warn("Could not find table " + tableName);
			return;
		}
		this.batchUpdate(Collections.singletonList(tableWrapper.removeRow(value)), tableWrapper);
	}

	/**
	 * Drop the table.
	 *
	 * @param tableName the name of the table to drop.
	 */
	public void dropTable(final String tableName) {
		TableWrapper tableWrapper = this.getTable(tableName);
		if (tableWrapper == null) {
			LogMsg.warn("Could not find table " + tableName);
			return;
		}
		this.batchUpdate(Collections.singletonList(tableWrapper.dropTable()), tableWrapper);
	}

	protected final void batchUpdate(@Nonnull final List<String> batchList, int resultSetType, int resultSetConcurrency) {
		final ArrayList<String> sqls = new ArrayList<>(batchList);
		if (!openConnection()) return;

		if (sqls.size() == 0)
			return;

		if (!hasStartWriteToDb)
			try {
				hasStartWriteToDb = true;
				final Statement statement = this.connection.createStatement(resultSetType, resultSetConcurrency);
				final int processedCount = sqls.size();

				// Prevent automatically sending db instructions
				this.connection.setAutoCommit(false);

				for (final String sql : sqls)
					statement.addBatch(sql);
				if (processedCount > 10_000)
					LogMsg.warn("Updating your database (" + processedCount + " entries)... PLEASE BE PATIENT THIS WILL TAKE "
							+ (processedCount > 50_000 ? "10-20 MINUTES" : "5-10 MINUTES") + " - If server will print a crash report, ignore it, update will proceed.");

				// Set the flag to start time notifications timer
				batchUpdateGoingOn = true;

				// Notify console that progress still is being made
				new Timer().scheduleAtFixedRate(new TimerTask() {

					@Override
					public void run() {
						if (batchUpdateGoingOn)
							LogMsg.warn("Still executing, DO NOT SHUTDOWN YOUR SERVER.");
						else
							cancel();
					}
				}, 1000 * 30, 1000 * 30);
				// Execute
				statement.executeBatch();

				// This will block the thread
				this.connection.commit();

			} catch (final Throwable t) {
				t.printStackTrace();

			} finally {
				try {
					this.connection.setAutoCommit(true);
					this.closeConnection();

				} catch (final SQLException ex) {
					ex.printStackTrace();
				}
				hasStartWriteToDb = false;
				// Even in case of failure, cancel
				batchUpdateGoingOn = false;
			}
	}

	protected void close(final PreparedStatement... preparedStatement) {
		if (preparedStatement == null) return;
		for (final PreparedStatement statement : preparedStatement)
			close(statement, null);
	}

	protected void close(final PreparedStatement preparedStatement, final ResultSet resultSet) {
		try {
			if (preparedStatement != null)
				preparedStatement.close();
			if (resultSet != null)
				resultSet.close();
		} catch (final SQLException ex) {
			ex.printStackTrace();
			/*LogMsg.close(CCH.getInstance(), ex);*/
		}

	}

	/**
	 * Update the table, if it missing a colum or columns.
	 *
	 * @param tableName the table you want to check.
	 * @return list of columns added in the database.
	 * @throws SQLException if something going wrong.
	 */
	protected List<String> updateTableColumnsInDb(final String tableName) throws SQLException {
		if (!openConnection()) return new ArrayList<>();
		if (this.connection.isClosed()) return new ArrayList<>();

		final List<String> column = new ArrayList<>();
		final PreparedStatement statement = this.connection.prepareStatement("SELECT * FROM " + tableName);
		final ResultSet rs = statement.executeQuery();
		final ResultSetMetaData rsmd = rs.getMetaData();
		final int columnCount = rsmd.getColumnCount();

		for (int i = 1; i <= columnCount; i++) {
			column.add(rsmd.getColumnName(i));
		}
		close(statement, rs);
		return column;
	}

	protected void dropColumn(final String createTableCmd, final List<String> existingColumns, final String tableName) throws SQLException {

		final TableWrapper updatedTableColumns = tables.get(tableName);

		if (updatedTableColumns == null || updatedTableColumns.getColumns().isEmpty()) return;
		// Remove the columns we don't want anymore from the table's list of columns

		if (this.removeColumns != null)
			for (final String removed : this.removeColumns) {
				existingColumns.remove(removed);
			}
		final String columnsSeparated = getColumnsFromTable(updatedTableColumns.getColumns().values());
		if (!openConnection()) return;

		// Rename the old table, so we can remove old name and rename columns.
		final PreparedStatement alterTable = connection.prepareStatement("ALTER TABLE " + tableName + " RENAME TO " + tableName + "_old;");
		// Creating the table on its new format (no redundant columns)
		final PreparedStatement createTable = connection.prepareStatement("CREATE TABLE IF NOT EXISTS " + tableName + " (" + columnsSeparated + ");");

		alterTable.execute();
		createTable.execute();

		// Populating the table with the data
		final PreparedStatement movedata = connection.prepareStatement("INSERT INTO " + tableName + "(" + TextUtils(existingColumns) + ") SELECT "
				+ TextUtils(existingColumns) + " FROM " + tableName + "_old;");
		movedata.execute();

		final PreparedStatement removeOldTable = connection.prepareStatement("DROP TABLE " + tableName + "_old;");
		removeOldTable.execute();

		close(movedata, alterTable, createTable, removeOldTable);
	}

	protected void createMissingColumns(final TableWrapper tableWrapper, final List<String> existingColumns) throws SQLException {
		if (existingColumns == null) return;
		if (!openConnection()) return;

		for (final Entry<String, TableRow> entry : tableWrapper.getColumns().entrySet()) {
			String columnName = entry.getKey();
			TableRow tableRow = entry.getValue();
			if (removeColumns.contains(columnName)) continue;
			if (existingColumns.contains(columnName)) continue;
			try {
				final PreparedStatement statement = connection.prepareStatement("ALTER TABLE `" + tableWrapper.getTableName() + "` ADD `" + columnName + "` " + tableRow.getDatatype() + ";");
				statement.execute();
			} catch (final SQLException throwable) {
				throwable.printStackTrace();
			}
		}
	}


	private void checkIfTableExist(String tableName, String columName) {
		try {
			if (this.connection == null) return;

			final PreparedStatement preparedStatement = this.connection.prepareStatement("SELECT * FROM `" + tableName + "` WHERE `" + columName + "` = ?");
			preparedStatement.setString(1, "");
			final ResultSet resultSet = preparedStatement.executeQuery();
			close(preparedStatement, resultSet);

		} catch (final SQLException ex) {
			LogMsg.warn("Unable to retrieve connection ", ex);
		}
	}


	protected String getColumnsFromTable(final Collection<TableRow> columns) {
		final StringBuilder columRow = new StringBuilder();
		for (final TableRow colum : columns) {
			columRow.append(colum).append(" ");
		}
		return columRow.toString();
	}

	protected List<String> getSqlsCommands(final List<String> listOfCommands, TableWrapper tableWrapper) {
		String sql = null;
		if (tableWrapper.getRecord() != null && !tableWrapper.getRecord().isEmpty()) {
			if (tableWrapper.getRecord().size() > 1) {
				listOfCommands.addAll(tableWrapper.updateTables());
			} else
				sql = tableWrapper.updateTable();
		} else {
			sql = tableWrapper.replaceIntoTable();
		}
		if (sql != null)
			listOfCommands.add(sql);

		return listOfCommands;
	}

	protected String TextUtils(final List<String> columns) {
		return columns.toString().replace("[", "").replace("]", "");
	}

	public boolean isHasStartWriteToDb() {
		return hasStartWriteToDb;
	}

	public Set<String> getRemoveColumns() {
		return removeColumns;
	}

	public void setRemoveColumns(final Set<String> removeColumns) {
		this.removeColumns = removeColumns;
	}

	/**
	 * Type of database set.
	 *
	 * @return the database type currently set.
	 */
	public DatabaseType getDatabaseType() {
		return databaseType;
	}

	@Nonnull
	public Map<String, TableWrapper> getTables() {
		return tables;
	}

	@Nullable
	public TableWrapper getTable(String tableName) {
		return tables.get(tableName);
	}

	/**
	 * Remove the table.
	 *
	 * @param tableName The table you want to remove
	 * @return true if it find the old table you want to remove.
	 */
	public boolean removeTable(String tableName) {
		return tables.remove(tableName) != null;
	}

	public void addTable(TableWrapper tableWrapper) {
		tables.put(tableWrapper.getTableName(), tableWrapper);
	}

	public boolean openConnection() {
		try {
			if (this.connection == null || this.connection.isClosed())
				this.connection = connect();
		} catch (SQLException e) {
			LogMsg.warn("Could not check if the connection is closed ", e);
			return false;
		}
		return true;
	}

	public void closeConnection() {
		try {
			if (this.connection != null) {
				this.connection.close();
			}
		} catch (final SQLException exception) {
			exception.printStackTrace();
		}
	}

	public Map<String, Object> getDataFromDB(final ResultSet resultSet) throws SQLException {
		final ResultSetMetaData rsmd = resultSet.getMetaData();
		final int columnCount = rsmd.getColumnCount();
		final Map<String, Object> objectMap = new HashMap<>();
		if (resultSet.next())
			for (int i = 1; i <= columnCount; i++) {
				objectMap.put(rsmd.getColumnName(i), resultSet.getObject(i));
			}
		return objectMap;
	}

	public boolean isHikariAvailable(final String path) {
		try {
			Class.forName(path);
		} catch (ClassNotFoundException e) {
			return false;
		}
		return true;
	}

	public abstract boolean isHasCastException();

}
