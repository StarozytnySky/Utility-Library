package org.broken.arrow.library.menu.shared;

import org.bukkit.inventory.ItemStack;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import javax.annotation.Nullable;
import java.util.UUID;

/** Immutable snapshot of a physical storage slot mutation and its initiating player. */
public final class SharedItemChange {
    private final int page;
    private final int slot;
    private final int storageSlot;
    private final ItemStack before;
    private final ItemStack after;
    private final UUID playerId;
    private final String playerName;
    private final UUID transactionId;
    private final UUID storageId;

    /**
     * Constructs a slot snapshot for transaction proposals.
     *
     * @param page the zero-based page index
     * @param slot the slot index associated with the operation
     * @param storageSlot the zero-based stable storage slot index
     * @param before the item or state before the operation
     * @param after the item or state after the operation
     */
    public SharedItemChange(int page, int slot, int storageSlot, @Nullable ItemStack before, @Nullable ItemStack after) {
        this(page, slot, storageSlot, before, after, null, null, null, null);
    }

    /**
     * Constructs a snapshot with historical transaction and player identity.
     *
     * @param page the zero-based page index
     * @param slot the slot index associated with the operation
     * @param storageSlot the zero-based stable storage slot index
     * @param before the item or state before the operation
     * @param after the item or state after the operation
     * @param playerId the UUID of the player at the time of the operation
     * @param playerName the name of the player at the time of the operation
     * @param transactionId the unique identifier of the inventory transaction
     * @param storageId the unique identity of the shared storage
     */
    public SharedItemChange(int page, int slot, int storageSlot, @Nullable ItemStack before, @Nullable ItemStack after,
                            @Nullable UUID playerId, @Nullable String playerName,
                            @Nullable UUID transactionId, @Nullable UUID storageId) {
        this.page = page;
        this.slot = slot;
        this.storageSlot = storageSlot;
        this.before = before == null ? null : before.clone();
        this.after = after == null ? null : after.clone();
        this.playerId = playerId;
        this.playerName = playerName;
        this.transactionId = transactionId;
        this.storageId = storageId;
    }

    /**
     * Returns a copy with populated initiating player identity and transaction ID.
     *
     * @param playerId the UUID of the player at the time of the operation
     * @param playerName the name of the player at the time of the operation
     * @param transactionId the unique identifier of the inventory transaction
     * @param storageId the unique identity of the shared storage
     * @return a detached change descriptor containing the supplied actor and transaction identity
     */
    public SharedItemChange withContext(UUID playerId, String playerName, UUID transactionId, UUID storageId) {
        return new SharedItemChange(page, slot, storageSlot, before, after, playerId, playerName, transactionId, storageId);
    }

    /**
     * Zero-based page.
     *
     * @return the zero-based page index
     */
    public int page() { return page; }
    /**
     * Visual GUI slot.
     *
     * @return the visual slot index within the page
     */
    public int slot() { return slot; }
    /**
     * Stable absolute storage index.
     *
     * @return the absolute stable storage slot index
     */
    public int storageSlot() { return storageSlot; }
    /**
     * Historical actor UUID, nullable for non-player changes.
     *
     * @return the UUID of the actor, if available
     */
    @Nullable public UUID playerId() { return playerId; }
    /**
     * Returns the currently online actor, if any. Use only on the Bukkit server thread.
     *
     * @return the online actor, or null if unavailable
     */
    @Nullable public Player player() { return playerId == null ? null : Bukkit.getPlayer(playerId); }
    /**
     * Player's name captured at transaction start, nullable for non-player changes.
     *
     * @return the online actor, or null if unavailable name
     */
    @Nullable public String playerName() { return playerName; }
    /**
     * Idempotency key of this transaction.
     *
     * @return the unique idempotency identifier of the transaction
     */
    @Nullable public UUID transactionId() { return transactionId; }
    /**
     * Identity of the shared storage.
     *
     * @return the UUID uniquely identifying the shared storage
     */
    @Nullable public UUID storageId() { return storageId; }
    /**
     * Detached copy of the item before this slot changed.
     *
     * @return a defensive copy of the previous item, or null for an empty slot
     */
    @Nullable public ItemStack before() { return before == null ? null : before.clone(); }
    /**
     * Detached copy of the item after this slot changed.
     *
     * @return a defensive copy of the resulting item, or null for an empty slot
     */
    @Nullable public ItemStack after() { return after == null ? null : after.clone(); }
    /**
     * Representative item, favoring the incoming item when present.
     *
     * @return a defensive copy of the item involved in the change
     */
    @Nullable public ItemStack item() { return after == null ? before() : after(); }
    /**
     * Absolute difference in stack quantities, or full incoming amount on a type replacement.
     *
     * @return the number of items in the stack
     */
    public int amount() {
        int oldCount = before == null ? 0 : before.getAmount();
        int newCount = after == null ? 0 : after.getAmount();
        if (before != null && after != null && !before.isSimilar(after)) return newCount;
        return Math.abs(newCount - oldCount);
    }
}
