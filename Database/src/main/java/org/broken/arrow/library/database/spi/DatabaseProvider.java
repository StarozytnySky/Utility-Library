package org.broken.arrow.library.database.spi;

import org.broken.arrow.library.database.api.DatabaseClient;
import org.broken.arrow.library.database.config.DatabaseKind;
import org.broken.arrow.library.database.schema.CollectionDefinition;
import org.broken.arrow.library.database.session.DatabaseWork;
import org.broken.arrow.library.database.session.ReadSession;
import org.broken.arrow.library.database.session.WriteSession;

/** Backend-specific session boundary. Implementations are owned by one DatabaseClient. */
public interface DatabaseProvider extends AutoCloseable {
    /** Describes the selected engine. */
    DatabaseKind kind();

    /** Whether safe multi-record transactional writes are supported by this deployment. */
    boolean supportsTransactions();

    /** Runs work in a borrowed read session and cleans up its connection/session. */
    <T> T read(DatabaseWork<ReadSession, T> work);

    /** Commits a transaction on success; rolls back on failure. */
    <T> T transaction(DatabaseWork<WriteSession, T> work);

    /** Runs one independent write operation, not a multi-statement transaction. */
    <T> T writeOne(DatabaseWork<WriteSession, T> work);

    /** Creates a missing table/collection without modifying an existing schema. */
    void createIfAbsent(CollectionDefinition schema);

    /** Releases all provider-owned resources. */
    @Override
    void close();
}
