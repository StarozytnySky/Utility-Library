package org.broken.arrow.library.database;

import org.broken.arrow.library.database.api.CollectionDefinition;
import org.broken.arrow.library.database.api.DatabaseClient;
import org.broken.arrow.library.database.api.DatabaseConfiguration;
import org.broken.arrow.library.database.api.DatabaseKind;
import org.broken.arrow.library.database.api.FieldType;
import org.broken.arrow.library.database.api.RecordCodec;
import org.broken.arrow.library.database.api.RecordQuery;
import org.broken.arrow.library.database.api.RecordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** Contract tests for portable operations on the embedded JDBC backends. */
class PortableRepositoryIntegrationTest {
    private static final CollectionDefinition SCHEMA = CollectionDefinition.named("accounts", "id", FieldType.UUID)
            .field("owner", FieldType.STRING, false)
            .field("balance", FieldType.LONG, false)
            .build();

    private record Account(UUID id, String owner, long balance) { }

    private static final RecordCodec<Account, UUID> CODEC = new RecordCodec<>() {
        @Override
        public CollectionDefinition schema() { return SCHEMA; }

        @Override
        public UUID id(Account account) { return account.id(); }

        @Override
        public Map<String, Object> encode(Account account) {
            return Map.of("id", account.id(), "owner", account.owner(), "balance", account.balance());
        }

        @Override
        public Account decode(Map<String, Object> fields) {
            return new Account((UUID) fields.get("id"), (String) fields.get("owner"), (long) fields.get("balance"));
        }
    };

    @TempDir
    Path temporary;

    /** SQLite and H2 must implement identical CRUD, conflict and conditional-write semantics. */
    @Test
    void portableCrudAndAtomicOperations() {
        runOnEmbeddedProviders(database -> {
            RecordRepository<Account, UUID> repository = database.repository(CODEC);
            UUID id = UUID.randomUUID();
            Account original = new Account(id, "alice", 100L);
            repository.insertAsync(original).join();
            assertEquals(original, repository.findByIdAsync(id).join().orElseThrow());
            assertFalse(repository.insertIfAbsentAsync(original).join());
            assertTrue(repository.existsAsync(id).join());
            assertTrue(repository.decrementIfEnoughAsync(id, "balance", 30).join());
            assertFalse(repository.decrementIfEnoughAsync(id, "balance", 71).join());
            assertTrue(repository.compareAndSetAsync(id, "balance", 70, 99).join());
            assertFalse(repository.compareAndSetAsync(id, "balance", 70, 5).join());
            assertEquals(99L, repository.findByIdAsync(id).join().orElseThrow().balance());
            assertEquals(1L, repository.countAsync(RecordQuery.all()).join().longValue());
            assertEquals(1, repository.findAsync(RecordQuery.all()
                    .where("balance", RecordQuery.Operator.GTE, 99L)).join().size());
            assertTrue(repository.deleteByIdAsync(id).join());
            assertTrue(repository.findByIdAsync(id).join().isEmpty());
        });
    }

    /** A failed callback must roll back both writes; no partial ledger/account state. */
    @Test
    void rollbackAndBatchAreAtomic() {
        runOnEmbeddedProviders(database -> {
            RecordRepository<Account, UUID> repository = database.repository(CODEC);
            Account first = new Account(UUID.randomUUID(), "one", 10);
            Account second = new Account(UUID.randomUUID(), "two", 20);
            assertThrows(RuntimeException.class, () -> database.transactionAsync(session -> {
                repository.insert(session, first);
                repository.insert(session, second);
                throw new IllegalStateException("Simulated failure after both mutations");
            }).join());
            assertEquals(0L, repository.countAsync(RecordQuery.all()).join().longValue());

            repository.insertBatchAsync(List.of(first, second), 1).join();
            assertEquals(2L, repository.countAsync(RecordQuery.all()).join().longValue());

            Account third = new Account(UUID.randomUUID(), "third", 30);
            assertThrows(RuntimeException.class, () -> repository.insertBatchAsync(List.of(third, first), 2).join());
            assertTrue(repository.findByIdAsync(third.id()).join().isEmpty());
        });
    }

    /** Atomic increments must reject values that would overflow a 64-bit balance. */
    @Test
    void integerOverflowDoesNotModifyBalance() {
        runOnEmbeddedProviders(database -> {
            RecordRepository<Account, UUID> repository = database.repository(CODEC);
            UUID id = UUID.randomUUID();
            repository.insertAsync(new Account(id, "ceiling", Long.MAX_VALUE)).join();
            assertFalse(repository.incrementAsync(id, "balance", 1L).join());
            assertEquals(Long.MAX_VALUE, repository.findByIdAsync(id).join().orElseThrow().balance());
            assertTrue(repository.incrementAsync(id, "balance", -1L).join());
            assertEquals(Long.MAX_VALUE - 1L, repository.findByIdAsync(id).join().orElseThrow().balance());
        });
    }

    /** Concurrent conditional decrements must never consume more stock than exists. */
    @Test
    void concurrentDecrementsRemainBounded() {
        runOnEmbeddedProviders(database -> {
            RecordRepository<Account, UUID> repository = database.repository(CODEC);
            UUID id = UUID.randomUUID();
            repository.insertAsync(new Account(id, "stock", 50)).join();
            List<CompletableFuture<Boolean>> futures = IntStream.range(0, 100)
                    .mapToObj(index -> repository.decrementIfEnoughAsync(id, "balance", 1))
                    .toList();
            long completed = futures.stream().filter(CompletableFuture::join).count();
            assertEquals(50L, completed);
            assertEquals(0L, repository.findByIdAsync(id).join().orElseThrow().balance());
        });
    }

    /** Runs one identical contract against both embedded JDBC engines. */
    private void runOnEmbeddedProviders(java.util.function.Consumer<DatabaseClient> test) {
        for (DatabaseKind kind : List.of(DatabaseKind.SQLITE, DatabaseKind.H2)) {
            String address = kind == DatabaseKind.SQLITE
                    ? "jdbc:sqlite:" + temporary.resolve("db-" + UUID.randomUUID() + ".sqlite")
                    : "jdbc:h2:mem:db_" + UUID.randomUUID().toString().replace('-', '_') + ";DB_CLOSE_DELAY=-1";
            DatabaseConfiguration configuration = DatabaseConfiguration.forKind(kind, address)
                    .poolSize(kind == DatabaseKind.SQLITE ? 1 : 4)
                    .workers(4, 512)
                    .connectionTimeout(Duration.ofSeconds(10))
                    .build();
            try (DatabaseClient database = DatabaseClient.open(configuration)) {
                database.createIfAbsentAsync(SCHEMA).join();
                test.accept(database);
            }
        }
    }
}
