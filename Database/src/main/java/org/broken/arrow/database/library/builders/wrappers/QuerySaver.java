package org.broken.arrow.database.library.builders.wrappers;

import org.broken.arrow.database.library.core.Database;
import org.broken.arrow.database.library.core.SQLDatabaseQuery;
import org.broken.arrow.database.library.utility.BatchExecutor;
import org.broken.arrow.database.library.utility.BatchExecutorUnsafe;
import org.broken.arrow.library.logging.Logging;
import org.broken.arrow.library.serialize.utility.serialize.ConfigurationSerializable;

import javax.annotation.Nonnull;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Represents a helper to set values before execute the save to the database.
 *
 * @param <K> the type of the map key used in the save operation
 * @param <V> the type of the value, which must implement {@link ConfigurationSerializable}
 */
public class QuerySaver<K, V extends ConfigurationSerializable> extends QueryContext<SaveRecord<K, V>> {
    @Nonnull
    private final Logging log = new Logging(QueryLoader.class);
    @Nonnull
    private final String tableName;
    @Nonnull
    private final Map<K, V> cacheToSave;
    @Nonnull
    final Consumer<SaveSetup> strategy;



    public QuerySaver(@Nonnull final SQLDatabaseQuery sqlDatabaseQuery, @Nonnull String tableName, @Nonnull Map<K, V> cacheToSave, @Nonnull Consumer<SaveSetup> strategy) {
        super(sqlDatabaseQuery,tableName);
        this.tableName = tableName;
        this.cacheToSave = cacheToSave;
        this.strategy = strategy;
    }


    public void save() {
        Database database = this.getSqlDatabaseQuery().getDatabase();
        final Connection connection = database.attemptToConnect();
        final BatchExecutor<SaveRecord<K, V>> batchExecutor;

        final DatabaseSettingsSave databaseSettings = new DatabaseSettingsSave(this.tableName);
        final DatabaseQueryHandler<SaveRecord<K, V>> databaseQueryHandler = new DatabaseQueryHandler<>(databaseSettings);
        if (connection == null) {
            database.printFailToOpen();
            return;
        }
        SaveSetup saveSetup = new SaveSetup();
        this.strategy.accept(saveSetup);
        saveSetup.applyConfigure(databaseSettings);

        final List<SaveRecord<K, V>> data = cacheToSave.entrySet().stream().map(kvEntry -> this.applyQuery(new SaveRecord<>(this.tableName, kvEntry))).collect(Collectors.toList());
        if (data.isEmpty()) {
            this.log.log(Level.WARNING, () -> "No data in the map for the table:'" + this.tableName + "' . Just must provide data and also don't forget to set your where clause.");
            return;
        }

        if (database.isSecureQuery())
            batchExecutor = new BatchExecutor<>(database, connection, data);
        else {
            batchExecutor = new BatchExecutorUnsafe<>(database, connection, data);
        }
        batchExecutor.save(tableName, databaseSettings.isShallUpdate(), databaseQueryHandler);
    }

}
