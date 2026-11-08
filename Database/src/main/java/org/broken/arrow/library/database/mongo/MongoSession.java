package org.broken.arrow.library.database.mongo;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.FindIterable;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.result.UpdateResult;
import org.broken.arrow.library.database.api.CollectionDefinition;
import org.broken.arrow.library.database.api.FieldType;
import org.broken.arrow.library.database.api.RecordQuery;
import org.broken.arrow.library.database.api.WriteSession;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Per-operation MongoDB session. Every transactional statement shares one ClientSession. */
final class MongoSession implements WriteSession {
    private final MongoDatabase database;
    private final ClientSession transaction;
    private final boolean oneMutationOnly;
    private int mutations;

    /** Creates a session; null means a single atomic document operation or plain read. */
    MongoSession(MongoDatabase database, ClientSession transaction) {
        this(database, transaction, false);
    }

    /** Enforces the portable single-document atomicity contract on standalone MongoDB. */
    MongoSession(MongoDatabase database, ClientSession transaction, boolean oneMutationOnly) {
        this.database = database;
        this.transaction = transaction;
        this.oneMutationOnly = oneMutationOnly;
    }

    private void beforeMutation() {
        if (oneMutationOnly && ++mutations > 1) {
            throw new IllegalStateException("writeOne may mutate exactly one document; use a transaction for multiple writes");
        }
    }

    private MongoCollection<Document> collection(CollectionDefinition schema) {
        return database.getCollection(schema.name());
    }

    @Override
    public Optional<Map<String, Object>> findById(CollectionDefinition schema, Object id) {
        MongoCollection<Document> collection = collection(schema);
        Bson filter = Filters.eq("_id", MongoValues.id(schema, id));
        Document value = transaction == null ? collection.find(filter).first() : collection.find(transaction, filter).first();
        return Optional.ofNullable(value).map(row -> MongoValues.decode(schema, row));
    }

    @Override
    public List<Map<String, Object>> find(CollectionDefinition schema, RecordQuery query) {
        query.validate(schema);
        MongoCollection<Document> collection = collection(schema);
        Bson predicate = filter(schema, query);
        FindIterable<Document> cursor = transaction == null ? collection.find(predicate) : collection.find(transaction, predicate);
        String order = MongoValues.name(schema, query.orderBy() == null ? schema.idField() : query.orderBy());
        cursor.sort(new Document(order, query.descending() ? -1 : 1)).skip(query.offset()).limit(query.limit());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Document document : cursor) rows.add(MongoValues.decode(schema, document));
        return List.copyOf(rows);
    }

    @Override
    public long count(CollectionDefinition schema, RecordQuery query) {
        query.validate(schema);
        MongoCollection<Document> collection = collection(schema);
        Bson predicate = filter(schema, query);
        return transaction == null ? collection.countDocuments(predicate) : collection.countDocuments(transaction, predicate);
    }

    @Override
    public void insert(CollectionDefinition schema, Map<String, Object> record) {
        beforeMutation();
        Document encoded = MongoValues.encode(schema, record);
        if (transaction == null) collection(schema).insertOne(encoded);
        else collection(schema).insertOne(transaction, encoded);
    }

    @Override
    public boolean insertIfAbsent(CollectionDefinition schema, Map<String, Object> record) {
        beforeMutation();
        Document encoded = MongoValues.encode(schema, record);
        Bson id = Filters.eq("_id", encoded.get("_id"));
        // This is one atomic conditional MongoDB write, including every field.
        Document initialValues = new Document(encoded);
        initialValues.remove("_id");
        Bson change = new Document("$setOnInsert", initialValues);
        com.mongodb.client.model.UpdateOptions options = new com.mongodb.client.model.UpdateOptions().upsert(true);
        UpdateResult result = transaction == null ? collection(schema).updateOne(id, change, options)
                : collection(schema).updateOne(transaction, id, change, options);
        return result.getUpsertedId() != null;
    }

    @Override
    public void insertBatch(CollectionDefinition schema, List<Map<String, Object>> records, int chunkSize) {
        if (chunkSize < 1 || chunkSize > 10_000) throw new IllegalArgumentException("Invalid batch size");
        if (records.isEmpty()) return;
        if (transaction == null) {
            throw new IllegalStateException("Atomic multi-record insert requires a MongoDB transaction");
        }
        for (int start = 0; start < records.size(); start += chunkSize) {
            List<Document> documents = new ArrayList<>();
            for (int index = start; index < Math.min(records.size(), start + chunkSize); index++) {
                documents.add(MongoValues.encode(schema, records.get(index)));
            }
            collection(schema).insertMany(transaction, documents);
        }
    }

    @Override
    public void upsert(CollectionDefinition schema, Map<String, Object> record) {
        beforeMutation();
        Document encoded = MongoValues.encode(schema, record);
        Bson id = Filters.eq("_id", encoded.get("_id"));
        ReplaceOptions options = new ReplaceOptions().upsert(true);
        if (transaction == null) collection(schema).replaceOne(id, encoded, options);
        else collection(schema).replaceOne(transaction, id, encoded, options);
    }

    @Override
    public boolean update(CollectionDefinition schema, Map<String, Object> record) {
        beforeMutation();
        Document encoded = MongoValues.encode(schema, record);
        Bson id = Filters.eq("_id", encoded.get("_id"));
        UpdateResult updated = transaction == null ? collection(schema).replaceOne(id, encoded)
                : collection(schema).replaceOne(transaction, id, encoded);
        return updated.getMatchedCount() > 0;
    }

    @Override
    public boolean deleteById(CollectionDefinition schema, Object id) {
        beforeMutation();
        Bson filter = Filters.eq("_id", MongoValues.id(schema, id));
        return (transaction == null ? collection(schema).deleteOne(filter)
                : collection(schema).deleteOne(transaction, filter)).getDeletedCount() > 0;
    }

    @Override
    public boolean increment(CollectionDefinition schema, Object id, String field, long amount) {
        beforeMutation();
        requireInteger(schema, field);
        List<Bson> constraints = new ArrayList<>();
        constraints.add(Filters.eq("_id", MongoValues.id(schema, id)));
        constraints.add(Filters.ne(field, null));
        if (amount > 0) constraints.add(Filters.lte(field, Long.MAX_VALUE - amount));
        else if (amount < 0) constraints.add(Filters.gte(field, Long.MIN_VALUE - amount));
        Bson match = Filters.and(constraints);
        UpdateResult result = transaction == null ? collection(schema).updateOne(match, Updates.inc(field, amount))
                : collection(schema).updateOne(transaction, match, Updates.inc(field, amount));
        return result.getMatchedCount() > 0;
    }

    @Override
    public boolean decrementIfEnough(CollectionDefinition schema, Object id, String field, long amount) {
        beforeMutation();
        requireInteger(schema, field);
        if (amount < 0) throw new IllegalArgumentException("amount must not be negative");
        Bson match = Filters.and(Filters.eq("_id", MongoValues.id(schema, id)), Filters.gte(field, amount));
        UpdateResult result = transaction == null ? collection(schema).updateOne(match, Updates.inc(field, -amount))
                : collection(schema).updateOne(transaction, match, Updates.inc(field, -amount));
        return result.getMatchedCount() > 0;
    }

    @Override
    public boolean compareAndSet(CollectionDefinition schema, Object id, String field, long expected, long updated) {
        beforeMutation();
        requireInteger(schema, field);
        Bson match = Filters.and(Filters.eq("_id", MongoValues.id(schema, id)), Filters.eq(field, expected));
        UpdateResult result = transaction == null ? collection(schema).updateOne(match, Updates.set(field, updated))
                : collection(schema).updateOne(transaction, match, Updates.set(field, updated));
        return result.getMatchedCount() > 0;
    }

    private static void requireInteger(CollectionDefinition schema, String field) {
        FieldType type = schema.field(field).type();
        if (field.equals(schema.idField()) || (type != FieldType.LONG)) {
            throw new IllegalArgumentException("Atomic numeric operation requires a mutable integer field");
        }
    }

    private static Bson filter(CollectionDefinition schema, RecordQuery query) {
        List<Bson> predicates = new ArrayList<>();
        for (RecordQuery.Condition condition : query.conditions()) {
            String field = MongoValues.name(schema, condition.field());
            Object value = MongoValues.encode(schema.field(condition.field()).type(), condition.value());
            predicates.add(switch (condition.operator()) {
                case EQ -> Filters.eq(field, value);
                case NE -> Filters.ne(field, value);
                case GT -> Filters.gt(field, value);
                case GTE -> Filters.gte(field, value);
                case LT -> Filters.lt(field, value);
                case LTE -> Filters.lte(field, value);
            });
        }
        return predicates.isEmpty() ? new Document() : Filters.and(predicates);
    }
}
