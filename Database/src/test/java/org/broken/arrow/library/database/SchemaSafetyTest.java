package org.broken.arrow.library.database;

import org.broken.arrow.library.database.config.DatabaseConfiguration;
import org.broken.arrow.library.database.config.DatabaseKind;
import org.broken.arrow.library.database.jdbc.SqlDialect;
import org.broken.arrow.library.database.repository.RecordQuery;
import org.broken.arrow.library.database.schema.CollectionDefinition;
import org.broken.arrow.library.database.schema.FieldType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Ensures schema and SQL generation never interpolate unchecked identifiers. */
class SchemaSafetyTest {
    @Test
    void identifiersAreValidatedAtConstructionAndExecution() {
        assertThrows(IllegalArgumentException.class, () -> CollectionDefinition.named("users;DROP_TABLE", "id", FieldType.UUID));
        CollectionDefinition schema = CollectionDefinition.named("users", "id", FieldType.UUID)
                .field("points", FieldType.LONG, false).build();
        assertThrows(IllegalArgumentException.class,
                () -> RecordQuery.all().where("unknown", RecordQuery.Operator.EQ, 1).validate(schema));
        assertThrows(IllegalArgumentException.class, () -> RecordQuery.all().orderBy("name--", false));
        assertTrue(new SqlDialect(DatabaseKind.H2).upsert(schema, java.util.List.of("id", "points"))
                .startsWith("MERGE INTO"));
    }

    @Test
    void secretsNeverAppearInDiagnosticRepresentation() {
        DatabaseConfiguration configuration = DatabaseConfiguration.forKind(DatabaseKind.POSTGRESQL,
                        "jdbc:postgresql://localhost:5432/game")
                .credentials("secret_login", "secret_password")
                .build();
        assertFalse(configuration.toString().contains("secret_password"));
        assertFalse(configuration.toString().contains("secret_login"));
    }
}
