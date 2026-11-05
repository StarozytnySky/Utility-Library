package org.broken.arrow.library.menu.shared;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for the optional callback context without a live Bukkit server. */
final class SharedTransactionTest {
    /** Immutable transactions preserve the actor name snapshot and both slot coordinates. */
    @Test void transactionProvidesStableHistoricalIdentity() {
        UUID id = UUID.randomUUID();
        UUID storage = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        SharedItemChange change = new SharedItemChange(2, 12, 102, null, null);
        SharedInventoryTransaction transaction = new SharedInventoryTransaction(id, storage, player,
                "Steve", SharedOperation.CURSOR, 7L, Collections.singletonList(change), Collections.emptyMap(), Collections.emptyMap(), null, null);
        assertEquals("Steve", transaction.playerName());
        assertEquals(player, transaction.changes().get(0).playerId());
        assertEquals(102, transaction.changes().get(0).storageSlot());
        assertEquals(12, transaction.changes().get(0).slot());
        assertEquals(id, transaction.changes().get(0).transactionId());
        assertThrows(UnsupportedOperationException.class, () -> transaction.changes().clear());
    }

    /** Empty player destination snapshots must survive immutable copying for SQL validation. */
    @Test void nullPlayerDestinationIsPreserved() {
        UUID transactionId = UUID.randomUUID();
        java.util.Map<Integer, org.bukkit.inventory.ItemStack> before = new java.util.HashMap<>();
        before.put(7, null);
        java.util.Map<Integer, org.bukkit.inventory.ItemStack> after = new java.util.HashMap<>();
        after.put(7, null);
        SharedInventoryTransaction transaction = new SharedInventoryTransaction(transactionId, UUID.randomUUID(),
                UUID.randomUUID(), "Alex", SharedOperation.SHIFT_WITHDRAW, 0L,
                Collections.singletonList(new SharedItemChange(0, 0, 0, null, null)), before, after, null, null);
        assertTrue(transaction.beforePlayerItems().containsKey(7));
        assertNull(transaction.beforePlayerItems().get(7));
        assertTrue(transaction.afterPlayerItems().containsKey(7));
    }
}
