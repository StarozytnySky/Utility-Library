package org.broken.arrow.library.database.spi;

import org.broken.arrow.library.database.api.DatabaseClient;
import org.broken.arrow.library.database.config.DatabaseKind;
import org.broken.arrow.library.database.schema.CollectionDefinition;
import org.broken.arrow.library.database.session.DatabaseWork;
import org.broken.arrow.library.database.session.ReadSession;
import org.broken.arrow.library.database.session.WriteSession;

/** Backend-specific session boundary. Implementations are owned by one DatabaseClient. */
public interface DatabaseProvider extends AutoCloseable {
    /**
     * Describes the selected engine.
     *
     * @return configured database engine
     */
    DatabaseKind kind();

    /**
     * Whether safe multi-record transactional writes are supported by this deployment.
     *
     * @return whether multi-record transactions are supported
     */
    boolean supportsTransactions();

    /**
     * Runs work in a borrowed read session and cleans up its connection/session.
     *
     * @param <T> type of the record or value returned by the operation
     * @param work database operation executed within the provided session
     * @return value produced by the database read callback
     */
    <T> T read(DatabaseWork<ReadSession, T> work);

    /**
     * Commits a transaction on success; rolls back on failure.
     *
     * @param <T> type of the record or value returned by the operation
     * @param work database operation executed within the provided session
     * @return value returned after the transaction commits successfully
     */
    <T> T transaction(DatabaseWork<WriteSession, T> work);

    /**
     * Runs one independent write operation, not a multi-statement transaction.
     *
     * @param <T> type of the record or value returned by the operation
     * @param work database operation executed within the provided session
     * @return value produced by the committed single-write operation
     */
    <T> T writeOne(DatabaseWork<WriteSession, T> work);

    /**
     * Creates a missing table/collection without modifying an existing schema.
     *
     * @param schema definition of the target table or collection
     */
    void createIfAbsent(CollectionDefinition schema);

    /** Releases all provider-owned resources. */
    @Override
    void close();
}
