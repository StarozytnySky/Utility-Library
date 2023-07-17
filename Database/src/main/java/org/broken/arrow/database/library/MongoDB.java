package org.broken.arrow.database.library;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.ServerApi;
import com.mongodb.ServerApiVersion;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import org.broken.arrow.database.library.builders.ConnectionSettings;
import org.broken.arrow.database.library.builders.DataWrapper;
import org.broken.arrow.database.library.builders.LoadDataWrapper;
import org.broken.arrow.database.library.builders.tables.TableRow;
import org.broken.arrow.database.library.builders.tables.TableWrapper;
import org.broken.arrow.database.library.log.LogMsg;
import org.broken.arrow.database.library.log.Validate;
import org.broken.arrow.serialize.library.utility.serialize.ConfigurationSerializable;
import org.bson.Document;
import org.bson.conversions.Bson;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class MongoDB extends Database {

	private final String startSQLUrl;
	private final String driver;
	private final ConnectionSettings preferences;
	private MongoClient mongoClient;
	private boolean isClosed;

	public MongoDB(final ConnectionSettings preferences) {
		this.preferences = preferences;
		this.startSQLUrl = "mongodb://";
		this.driver = "com.mongodb.Driver";
		connect();
	}

	@Override
	public void saveAll(@Nonnull final String tableName, @Nonnull final List<DataWrapper> dataWrapperList) {
		this.saveAll(tableName, dataWrapperList, false);
	}

	@Override
	public void saveAll(@Nonnull final String tableName, @Nonnull final List<DataWrapper> dataWrapperList, final boolean shallUpdate) {
		final TableWrapper tableWrapper = this.getTable(tableName);
		if (tableWrapper == null) {
			LogMsg.warn("Could not find table " + tableName);
			return;
		}
		if (!openConnection()) return;
		MongoDatabase database = mongoClient.getDatabase(preferences.getDatabaseName());
		MongoCollection<Document> collection = database.getCollection(tableName);
		for (DataWrapper dataWrapper : dataWrapperList) {
			Document document = new Document("_id", dataWrapper.getPrimaryValue());
			Bson filter = Filters.eq("_id", dataWrapper.getPrimaryValue());

			for (Entry<String, Object> entry : dataWrapper.getConfigurationSerialize().serialize().entrySet()) {
				TableRow column = tableWrapper.getColumn(entry.getKey());

				if (column == null) continue;
				Bson updatedRow = Updates.set(entry.getKey(), entry.getValue());
				UpdateResult updateResult = collection.updateOne(filter, updatedRow);
				if (updateResult.getMatchedCount() == 0)
					document.append(entry.getKey(), entry.getValue());
			}
			if (document.size() > 1)
				collection.insertOne(document);
		}

		// Close the MongoDB connection
		this.closeConnection();
	}

	@Override
	public void save(@Nonnull final String tableName, @Nonnull final DataWrapper dataWrapper) {
		this.save(tableName, dataWrapper, false);
	}

	@Override
	public void save(@Nonnull final String tableName, @Nonnull final DataWrapper dataWrapper, final boolean shallUpdate) {
		final TableWrapper tableWrapper = this.getTable(tableName);
		if (tableWrapper == null) {
			LogMsg.warn("Could not find table " + tableName);
			return;
		}
		if (!openConnection()) return;
		MongoDatabase database = mongoClient.getDatabase(preferences.getDatabaseName());
		MongoCollection<Document> collection = database.getCollection(tableName);

		Document document = new Document("_id", dataWrapper.getPrimaryValue());
		Bson filter = Filters.eq("_id", dataWrapper.getPrimaryValue());

		for (Entry<String, Object> entry : dataWrapper.getConfigurationSerialize().serialize().entrySet()) {
			TableRow column = tableWrapper.getColumn(entry.getKey());

			if (column == null) continue;
			Bson updatedRow = Updates.set(entry.getKey(), entry.getValue());
			UpdateResult updateResult = collection.updateOne(filter, updatedRow);
			if (updateResult.getMatchedCount() == 0)
				document.append(entry.getKey(), entry.getValue());
		}
		if (document.size() > 1)
			collection.insertOne(document);

		// Close the MongoDB connection
		this.closeConnection();
	}

	@Nullable
	@Override
	public <T extends ConfigurationSerializable> List<LoadDataWrapper<T>> loadAll(@Nonnull final String tableName, @Nonnull final Class<T> clazz) {
		TableWrapper tableWrapper = this.getTable(tableName);
		if (tableWrapper == null) {
			LogMsg.warn("Could not find table " + tableName);
			return null;
		}
		if (!openConnection()) return null;
		Validate.checkNotNull(tableWrapper.getPrimaryRow(), "Primary column should not be null");

		final List<LoadDataWrapper<T>> loadDataWrappers = new ArrayList<>();
		MongoDatabase database = mongoClient.getDatabase(preferences.getDatabaseName());
		// Retrieve data from MongoDB
		MongoCollection<Document> collection = database.getCollection(tableName);

		Document query = new Document(); // Replace with your specific query if needed
		FindIterable<Document> documents = collection.find(query);
		// Check if documents has results
		if (documents.iterator().hasNext()) {
			// Iterate over the documents and process each document
			for (Document document : documents) {
				Object id = document.get("_id");

				Map<String, Object> resultMap = new HashMap<>();
				for (Map.Entry<String, Object> entry : document.entrySet()) {
					String key = entry.getKey();
					Object value = entry.getValue();
					resultMap.put(key, value);

				}
				T deserialize = this.getMethodReflectionUtils().invokeDeSerializeMethod(clazz, "deserialize", resultMap);
				loadDataWrappers.add(new LoadDataWrapper<>(id, deserialize));
			}
		} else {
			LogMsg.info("Could not find any row within this table " + tableName);
		}
		this.closeConnection();
		return loadDataWrappers;
	}

	@Nullable
	@Override
	public <T extends ConfigurationSerializable> LoadDataWrapper<T> load(@Nonnull final String tableName, @Nonnull final Class<T> clazz, final String columnValue) {
		TableWrapper tableWrapper = this.getTable(tableName);
		if (tableWrapper == null) {
			LogMsg.warn("Could not find table " + tableName);
			return null;
		}
		if (!openConnection()) return null;
		Validate.checkNotNull(tableWrapper.getPrimaryRow(), "Primary column should not be null");

		LoadDataWrapper<T> loadDataWrapper = null;
		MongoDatabase database = mongoClient.getDatabase(preferences.getDatabaseName());
		MongoCollection<Document> collection = database.getCollection(tableName);

		Document query = new Document();
		query.append("_id", columnValue);
		FindIterable<Document> documents = collection.find(query);
		if (documents.iterator().hasNext()) {
			for (Document document : documents) {
				Object id = document.get("_id");
				if (!columnValue.equals(id)) continue;

				Map<String, Object> resultMap = new HashMap<>();
				for (Map.Entry<String, Object> entry : document.entrySet()) {
					String key = entry.getKey();
					Object value = entry.getValue();
					resultMap.put(key, value);
				}
				T deserialize = this.getMethodReflectionUtils().invokeDeSerializeMethod(clazz, "deserialize", resultMap);
				loadDataWrapper = new LoadDataWrapper<>(id, deserialize);
			}
		} else {
			LogMsg.info("Could not find any row with this value " + columnValue);
		}
		// Close the MongoDB connection
		this.closeConnection();
		return loadDataWrapper;
	}

	@Override
	public Connection connect() {
		//if (this.mongoClient != null) return null;
		String databaseName = preferences.getDatabaseName();
		String hostAddress = preferences.getHostAddress();
		String port = preferences.getPort();
		String user = preferences.getUser();
		String password = preferences.getPassword();
		String extra = preferences.getQuery();
		String credentials = "";
		if (user != null && password != null)
			if (!user.isEmpty() && !password.isEmpty())
				credentials = user + ":" + password + "@";

		ConnectionString connection = new ConnectionString(startSQLUrl + credentials + hostAddress + ":" + port + "/" + databaseName + extra);

		MongoClientSettings settings = MongoClientSettings.builder().applyConnectionString(connection)
				.serverApi(ServerApi.builder()
						.version(ServerApiVersion.V1).build()).build();

		this.mongoClient = MongoClients.create(settings);
		return null;
	}

	@Override
	public void createTables() {
		if (!openConnection()) return;
		MongoDatabase database = mongoClient.getDatabase(preferences.getDatabaseName());
		for (final Entry<String, TableWrapper> entityTables : this.getTables().entrySet()) {
			boolean collectionExists = database.listCollectionNames().into(new ArrayList<>()).contains(entityTables.getKey());
			if (!collectionExists) {
				database.createCollection(entityTables.getKey());
			}
		}
		this.closeConnection();
	}

	@Override
	protected void batchUpdate(@Nonnull final List<String> batchList, @Nonnull final TableWrapper... tableWrappers) {

	}

	@Override
	public boolean openConnection() {
		if (isClosed())
			connect();
		this.isClosed = false;
		return this.mongoClient != null;
	}

	@Override
	public void closeConnection() {
		if (mongoClient == null) return;
		mongoClient.close();
		this.isClosed = true;
	}

	@Override
	public boolean isClosed() {
		return mongoClient == null || isClosed;
	}

	@Override
	public boolean isHasCastException() {
		return false;
	}
}