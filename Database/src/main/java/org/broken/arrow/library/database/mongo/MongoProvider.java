package org.broken.arrow.library.database.mongo;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.broken.arrow.library.database.api.DatabaseFailure;
import org.broken.arrow.library.database.config.DatabaseConfiguration;
import org.broken.arrow.library.database.config.DatabaseKind;
import org.broken.arrow.library.database.schema.CollectionDefinition;
import org.broken.arrow.library.database.session.DatabaseWork;
import org.broken.arrow.library.database.session.ReadSession;
import org.broken.arrow.library.database.session.WriteSession;
import org.broken.arrow.library.database.spi.DatabaseProvider;
import org.bson.Document;

/** MongoDB backend with a built-in pool and explicit multi-document transaction detection. */
public final class MongoProvider implements DatabaseProvider {
    private final MongoClient client;
    private final MongoDatabase database;
    private final boolean transactionsSupported;

    /**
     * Opens a shared client and verifies topology, authentication and connectivity.
     *
     * @param config validated connection and worker configuration
     */
    public MongoProvider(DatabaseConfiguration config) {
        if (config.kind() != DatabaseKind.MONGODB) throw new IllegalArgumentException("MongoDB kind required");
        MongoClientSettings.Builder builder = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(config.address()));
        if (config.username() != null && config.password() != null) {
            builder.credential(com.mongodb.MongoCredential.createCredential(
                    config.username(), config.database(), config.password().toCharArray()));
        }
        MongoClientSettings settings = builder
                .applyToConnectionPoolSettings(pool -> pool.maxSize(config.poolSize()))
                .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(
                        config.connectionTimeout().toMillis(), TimeUnit.MILLISECONDS))
                .build();
        MongoClient candidate = MongoClients.create(settings);
        try {
            MongoDatabase target = candidate.getDatabase(config.database()).withWriteConcern(WriteConcern.MAJORITY);
            Document hello = candidate.getDatabase("admin").runCommand(new Document("hello", 1));
            // MongoDB standalone does not support multi-document transactions.
            transactionsSupported = hello.containsKey("setName") || "isdbgrid".equals(hello.getString("msg"));
            database = target;
            client = candidate;
        } catch (RuntimeException failure) {
            candidate.close();
            throw wrap("Connect MongoDB", failure);
        }
    }

    @Override
    public DatabaseKind kind() { return DatabaseKind.MONGODB; }

    @Override
    public boolean supportsTransactions() { return transactionsSupported; }

    @Override
    public <T> T read(DatabaseWork<ReadSession, T> work) {
        Objects.requireNonNull(work, "work");
        try {
            return work.execute(new MongoSession(database, null));
        } catch (DatabaseFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw wrap("MongoDB read", failure);
        }
    }

    @Override
    public <T> T writeOne(DatabaseWork<WriteSession, T> work) {
        Objects.requireNonNull(work, "work");
        try {
            // Each single-document mutation is atomic without a multi-document transaction.
            return work.execute(new MongoSession(database, null, true));
        } catch (DatabaseFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw wrap("MongoDB single mutation", failure);
        }
    }

    @Override
    public <T> T transaction(DatabaseWork<WriteSession, T> work) {
        Objects.requireNonNull(work, "work");
        if (!transactionsSupported) {
            throw new DatabaseFailure(DatabaseFailure.Reason.UNSUPPORTED,
                    "MongoDB deployment must be a replica set or sharded cluster for multi-document transactions");
        }
        try (ClientSession session = client.startSession()) {
            session.startTransaction(TransactionOptions.builder().writeConcern(WriteConcern.MAJORITY).build());
            T result;
            try {
                result = work.execute(new MongoSession(database, session));
            } catch (Exception failure) {
                try {
                    session.abortTransaction();
                } catch (RuntimeException abortFailure) {
                    failure.addSuppressed(abortFailure);
                }
                throw failure;
            }
            try {
                // No automatic transaction retry: arbitrary business callbacks may not be idempotent.
                session.commitTransaction();
            } catch (MongoException uncertain) {
                // Driver labels distinguish transient failures, but a failed commit may have persisted.
                throw new DatabaseFailure(DatabaseFailure.Reason.COMMIT_OUTCOME_UNKNOWN,
                        "MongoDB commit outcome is unknown; reconcile using an application operation ID", uncertain);
            }
            return result;
        } catch (DatabaseFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw wrap("MongoDB transaction", failure);
        }
    }

    @Override
    public void createIfAbsent(CollectionDefinition schema) {
        Objects.requireNonNull(schema, "schema");
        try {
            boolean exists = false;
            for (String name : database.listCollectionNames()) {
                if (schema.name().equals(name)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                try {
                    database.createCollection(schema.name());
                } catch (MongoCommandException failure) {
                    if (failure.getErrorCode() != 48) throw failure;
                }
            }
            for (CollectionDefinition.Index index : schema.indexes()) {
                java.util.List<String> columns = index.columns().stream()
                        .map(field -> MongoValues.name(schema, field)).toList();
                database.getCollection(schema.name()).createIndex(
                        com.mongodb.client.model.Indexes.ascending(columns),
                        new com.mongodb.client.model.IndexOptions()
                                .name(index.name()).unique(index.unique()));
            }
        } catch (MongoException failure) {
            throw wrap("Initialize MongoDB schema and indexes", failure);
        }
    }

    @Override
    public void close() { client.close(); }

    /**
     * Classifies driver failures without exposing connection URLs or record values.
     *
     * @param operation name of the database operation used for diagnostics
     * @param failure underlying error that caused the operation to fail
     * @return computed result of the requested operation
     */
    static DatabaseFailure wrap(String operation, Throwable failure) {
        DatabaseFailure.Reason reason = failure instanceof MongoWriteException write && write.getError().getCode() == 11000
                ? DatabaseFailure.Reason.CONFLICT : DatabaseFailure.Reason.OPERATION;
        return new DatabaseFailure(reason, operation + " failed", failure);
    }
}
