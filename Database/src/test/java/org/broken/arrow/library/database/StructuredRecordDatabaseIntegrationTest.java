package org.broken.arrow.library.database;

import org.broken.arrow.library.database.api.DatabaseClient;
import org.broken.arrow.library.database.api.DatabaseConfiguration;
import org.broken.arrow.library.database.api.DatabaseKind;
import org.broken.arrow.library.database.api.RecordCodec;
import org.broken.arrow.library.database.api.RecordCodecs;
import org.broken.arrow.library.database.api.RecordRepository;
import org.broken.arrow.library.database.api.StoredField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Integration coverage for structured values stored as SQL JSON text. */
class StructuredRecordDatabaseIntegrationTest {
    private enum Permission { INVITE, MANAGE, CLAIM }

    private record Visual(String color, Set<String> gradient) { }

    private record Role(UUID roleId, UUID guildId, String roleKey,
                        Set<Permission> permissions, List<Visual> visuals,
                        Map<String, Long> limits,
                        @StoredField(nullable = true) Instant expiresAt) { }

    @TempDir Path temporary;

    /** Confirms real SQLite/H2 reads restore nested collections and exact long values. */
    @Test
    void structuredRoleSurvivesRoundTrip() {
        RecordCodec<Role, UUID> codec = RecordCodecs.of(Role.class, "guild_roles", "roleId");
        for (DatabaseKind kind : List.of(DatabaseKind.SQLITE, DatabaseKind.H2)) {
            String url = kind == DatabaseKind.SQLITE
                    ? "jdbc:sqlite:" + temporary.resolve("roles-" + UUID.randomUUID() + ".sqlite")
                    : "jdbc:h2:mem:roles_" + UUID.randomUUID().toString().replace('-', '_') + ";DB_CLOSE_DELAY=-1";
            DatabaseConfiguration configuration = DatabaseConfiguration.forKind(kind, url)
                    .poolSize(kind == DatabaseKind.SQLITE ? 1 : 2)
                    .workers(2, 128)
                    .connectionTimeout(Duration.ofSeconds(10))
                    .build();
            try (DatabaseClient client = DatabaseClient.open(configuration)) {
                RecordRepository<Role, UUID> repository = client.repository(codec);
                repository.ensureSchemaAsync().join();
                Role original = new Role(UUID.randomUUID(), UUID.randomUUID(), "member",
                        Set.of(Permission.INVITE, Permission.CLAIM),
                        List.of(new Visual("#AABBCC", Set.of("#123456", "#654321"))),
                        Map.of("maxClaims", Long.MAX_VALUE), null);
                repository.insertAsync(original).join();
                assertEquals(original, repository.findByIdAsync(original.roleId()).join().orElseThrow());
            }
        }
    }
}
