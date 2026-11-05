package org.broken.arrow.library.menu.shared;

import org.bukkit.inventory.ItemStack;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable transaction proposal. Bukkit Player and Inventory objects are intentionally not retained.
 * Player item keys use Bukkit PlayerInventory indices (0-35, or 40 for offhand).
 * All returned item stacks are detached copies; the proposal can safely travel to a database worker.
 */
public final class SharedInventoryTransaction {
    private final UUID transactionId;
    private final UUID storageId;
    private final UUID playerId;
    private final String playerName;
    private final SharedOperation action;
    private final long expectedRevision;
    private final List<SharedItemChange> changes;
    private final Map<Integer, ItemStack> beforePlayerItems;
    private final Map<Integer, ItemStack> afterPlayerItems;
    private final ItemStack beforeCursor;
    private final ItemStack afterCursor;

    /**
     * Creates a transaction from fully validated inventory snapshots.
     *
     * @param transactionId the unique identifier of the inventory transaction
     * @param storageId the unique identity of the shared storage
     * @param playerId the UUID of the player at the time of the operation
     * @param playerName the name of the player at the time of the operation
     * @param action the action or reason for the inventory change
     * @param expectedRevision the revision that must match before committing changes
     * @param changes the individual slot changes included in the transaction
     * @param beforePlayerItems the player inventory contents before the transaction
     * @param afterPlayerItems the player inventory contents after the transaction
     * @param beforeCursor the cursor contents before the transaction
     * @param afterCursor the cursor contents after the transaction
     */
    public SharedInventoryTransaction(UUID transactionId, UUID storageId, UUID playerId, String playerName,
                                      SharedOperation action, long expectedRevision, List<SharedItemChange> changes,
                                      Map<Integer, ItemStack> beforePlayerItems, Map<Integer, ItemStack> afterPlayerItems,
                                      @Nullable ItemStack beforeCursor, @Nullable ItemStack afterCursor) {
        this.transactionId = transactionId;
        this.storageId = storageId;
        this.playerId = playerId;
        this.playerName = playerName;
        this.action = action;
        this.expectedRevision = expectedRevision;
        List<SharedItemChange> detachedChanges = new java.util.ArrayList<>(changes.size());
        for (SharedItemChange change : changes) {
            detachedChanges.add(change.withContext(playerId, playerName, transactionId, storageId));
        }
        this.changes = Collections.unmodifiableList(detachedChanges);
        this.beforePlayerItems = copy(beforePlayerItems);
        this.afterPlayerItems = copy(afterPlayerItems);
        this.beforeCursor = cloneItem(beforeCursor);
        this.afterCursor = cloneItem(afterCursor);
    }

    private static Map<Integer, ItemStack> copy(Map<Integer, ItemStack> source) {
        Map<Integer, ItemStack> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(key, cloneItem(item)));
        return Collections.unmodifiableMap(result);
    }

    @Nullable private static ItemStack cloneItem(@Nullable ItemStack item) { return item == null ? null : item.clone(); }
    /**
     * Unique, retry-safe identifier of this operation.
     *
     * @return the unique idempotency identifier of the transaction
     */
    public UUID transactionId() { return transactionId; }
    /**
     * Identifier of the shared storage.
     *
     * @return the UUID uniquely identifying the shared storage
     */
    public UUID storageId() { return storageId; }
    /**
     * Stable UUID of the user who initiated this operation.
     *
     * @return the UUID of the actor, if available
     */
    public UUID playerId() { return playerId; }
    /**
     * Historical name captured when the operation started.
     *
     * @return the online actor, or null if unavailable name
     */
    public String playerName() { return playerName; }
    /**
     * How the mutation was triggered.
     *
     * @return the action that initiated the transaction
     */
    public SharedOperation action() { return action; }
    /**
     * Storage revision at the time of proposal creation.
     *
     * @return the expected storage revision used for optimistic validation
     */
    public long expectedRevision() { return expectedRevision; }
    /**
     * All changed storage slots, including internal moves and swaps.
     *
     * @return the immutable collection of storage slot changes
     */
    public List<SharedItemChange> changes() { return changes; }
    /**
     * Expected player inventory slots before the operation.
     *
     * @return a defensive copy of the previous item, or null for an empty slot player items
     */
    public Map<Integer, ItemStack> beforePlayerItems() { return copy(beforePlayerItems); }
    /**
     * Player inventory slots to apply after successful commit.
     *
     * @return a defensive copy of the resulting item, or null for an empty slot player items
     */
    public Map<Integer, ItemStack> afterPlayerItems() { return copy(afterPlayerItems); }
    /**
     * Cursor contents when the operation was requested.
     *
     * @return a defensive copy of the previous item, or null for an empty slot cursor
     */
    @Nullable public ItemStack beforeCursor() { return cloneItem(beforeCursor); }
    /**
     * Cursor contents after the operation.
     *
     * @return a defensive copy of the resulting item, or null for an empty slot cursor
     */
    @Nullable public ItemStack afterCursor() { return cloneItem(afterCursor); }
}
