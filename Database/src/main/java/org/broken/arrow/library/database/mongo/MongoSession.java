package org.broken.arrow.library.database.mongo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mongodb.client.ClientSession;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import org.broken.arrow.library.database.repository.RecordQuery;
import org.broken.arrow.library.database.schema.CollectionDefinition;
import org.broken.arrow.library.database.schema.FieldType;
import org.broken.arrow.library.database.session.WriteSession;
import org.bson.Document;
import org.bson.conversions.Bson;

/** Per-operation MongoDB session. Every transactional statement shares one ClientSession. */
final class MongoSession implements WriteSession {
    private final MongoDatabase database;
    private final ClientSession transaction;
    private final boolean oneMutationOnly;
    private int mutations;

    /**
     * Creates a session; null means a single atomic document operation or plain read.
     *
     * @param database active database connection or namespace
     * @param transaction MongoDB client session used for transactional operations
     */
    MongoSession(MongoDatabase database, ClientSession transaction) {
        this(database, transaction, false);
    }

    /**
     * Enforces the portable single-document atomicity contract on standalone MongoDB.
     *
     * @param database active database connection or namespace
     * @param transaction MongoDB client session used for transactional operations
     * @param oneMutationOnly whether the session must reject a second write
     */
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
        Document sorting = new Document(order, query.descending() ? -1 : 1);
        if (query.orderBy() != null && !query.orderBy().equals(schema.idField())) {
            sorting.append("_id", query.descending() ? -1 : 1);
        }
        cursor.sort(sorting).skip(query.offset()).limit(query.limit());
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

    /** Uses MongoDB aggregation rather than materializing every matching document. */
    @Override
    public long sumLong(CollectionDefinition schema, RecordQuery query, String field) {
        requireLong(schema, field);
        List<Bson> pipeline = List.of(
                new Document("$match", filter(schema, query)),
                new Document("$group", new Document("_id", null)
                        .append("total", new Document("$sum", "$" + MongoValues.name(schema, field)))));
        var results = transaction == null ? collection(schema).aggregate(pipeline)
                : collection(schema).aggregate(transaction, pipeline);
        Document first = results.first();
        return first == null ? 0L : exactLong(first.get("total"));
    }

    /** Calculates the top grouped totals on the server, with a deterministic tie-breaker. */
    @Override
    public List<org.broken.arrow.library.database.repository.GroupedLongSum> groupSumLong(
            CollectionDefinition schema, RecordQuery query, String groupField, String sumField, int limit) {
        requireLong(schema, sumField);
        FieldType type = schema.field(groupField).type();
        if (type == FieldType.JSON || type == FieldType.BYTES
                || schema.field(groupField).nullable()) {
            throw new IllegalArgumentException("Group field must be non-null scalar");
        }
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("Group limit must be 1..1000");
        List<Bson> pipeline = List.of(new Document("$match", filter(schema, query)),
                new Document("$group", new Document("_id", "$" + MongoValues.name(schema, groupField))
                        .append("total", new Document("$sum", "$" + MongoValues.name(schema, sumField)))),
                new Document("$sort", new Document("total", -1).append("_id", 1)),
                new Document("$limit", limit));
        var results = transaction == null ? collection(schema).aggregate(pipeline)
                : collection(schema).aggregate(transaction, pipeline);
        List<org.broken.arrow.library.database.repository.GroupedLongSum> rows = new ArrayList<>();
        for (Document document : results) {
            rows.add(new org.broken.arrow.library.database.repository.GroupedLongSum(
                    MongoValues.decode(type, document.get("_id")), exactLong(document.get("total"))));
        }
        return List.copyOf(rows);
    }

    private static long exactLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof org.bson.types.Decimal128 decimal) {
            return decimal.bigDecimalValue().longValueExact();
        }
        if (value instanceof java.math.BigDecimal decimal) return decimal.longValueExact();
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        // MongoDB may promote overflows to double; that representation is not exact.
        throw new ArithmeticException("MongoDB aggregation did not return an exact integer");
    }

    private static void requireLong(CollectionDefinition schema, String field) {
        if (schema.field(field).type() != FieldType.LONG) {
            throw new IllegalArgumentException("SUM requires a LONG database field");
        }
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

    /** Performs a conditional multi-document update under a required transaction. */
    @Override
    public long updateMatching(CollectionDefinition schema, RecordQuery query, Map<String, Object> changes) {
        if (query.conditions().isEmpty()) {
            throw new IllegalArgumentException("Conditional updates require a WHERE predicate");
        }
        if (transaction == null) {
            throw new org.broken.arrow.library.database.api.DatabaseFailure(
                    org.broken.arrow.library.database.api.DatabaseFailure.Reason.UNSUPPORTED,
                    "MongoDB conditional multi-record updates require a transaction");
        }
        if (changes.isEmpty()) throw new IllegalArgumentException("No fields to update");
        Document values = new Document();
        for (var entry : changes.entrySet()) {
            if (entry.getKey().equals(schema.idField())) {
                throw new IllegalArgumentException("Cannot mutate the identifier");
            }
            var field = schema.field(entry.getKey());
            schema.validateValue(field, entry.getValue());
            values.put(MongoValues.name(schema, field.name()), MongoValues.encode(field.type(), entry.getValue()));
        }
        beforeMutation();
        return collection(schema).updateMany(transaction, filter(schema, query),
                new Document("$set", values)).getModifiedCount();
    }

    /**
     * Deletes all matching documents in one MongoDB transaction.
     * Standalone deployments cannot silently degrade to multiple independent deletes.
     *
     * @param schema validated collection descriptor
     * @param query non-empty, schema-validated predicate
     * @return exact number of deleted documents
     */
    @Override
    public long deleteMatching(CollectionDefinition schema, RecordQuery query) {
        java.util.Objects.requireNonNull(query, "query");
        if (query.conditions().isEmpty()) {
            throw new IllegalArgumentException("Bulk delete requires a WHERE predicate");
        }
        if (transaction == null) {
            throw new org.broken.arrow.library.database.api.DatabaseFailure(
                    org.broken.arrow.library.database.api.DatabaseFailure.Reason.UNSUPPORTED,
                    "MongoDB bulk delete requires a multi-document transaction");
        }
        query.validate(schema);
        Bson predicate = filter(schema, query);
        beforeMutation();
        return collection(schema).deleteMany(transaction, predicate).getDeletedCount();
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
