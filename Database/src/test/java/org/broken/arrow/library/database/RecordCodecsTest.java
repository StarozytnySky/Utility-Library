package org.broken.arrow.library.database;

import java.time.Instant;
import java.util.UUID;

import org.broken.arrow.library.database.codec.RecordCodec;
import org.broken.arrow.library.database.codec.RecordCodecs;
import org.broken.arrow.library.database.schema.StoredField;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Validates automatic record mapping, including enums and nullable fields. */
class RecordCodecsTest {
    enum Phase { PREPARED, COMPLETE }

    public record Entry(UUID id, @StoredField(nullable = true) String description,
                        long amount, Phase phase, Instant createdAt) { }

    @Test
    void recordMappingRoundTripsNullableFieldsAndEnums() {
        RecordCodec<Entry, UUID> codec = RecordCodecs.of(Entry.class, "entries", "id");
        Entry entry = new Entry(UUID.randomUUID(), null, 42L, Phase.PREPARED, Instant.now());
        assertEquals(entry, codec.decode(codec.encode(entry)));
        assertEquals(entry.id(), codec.id(entry));
        assertTrue(codec.schema().field("description").nullable());
    }
}
