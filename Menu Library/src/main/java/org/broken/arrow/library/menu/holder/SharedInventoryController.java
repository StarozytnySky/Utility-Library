package org.broken.arrow.library.menu.holder;

import org.broken.arrow.library.menu.shared.SharedInventoryTransaction;
import org.broken.arrow.library.menu.shared.SharedItemChange;
import org.broken.arrow.library.menu.shared.SharedOperation;
import org.broken.arrow.library.menu.shared.SharedTransferMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Produces pure, bounded transfer proposals. No Bukkit inventory or cursor is changed while
 * processing InventoryClickEvent/InventoryDragEvent; the owning menu commits the result later.
 */
final class SharedInventoryController {
    private final MenuHolderShared<?> owner;

    /**
     * Binds the simulator to a shared menu instance.
     *
     * @param owner the shared inventory menu that owns the controller
     */
    SharedInventoryController(MenuHolderShared<?> owner) { this.owner = owner; }

    /**
     * Simulates one click; unsupported or no-op actions return null.
     *
     * @param player the player performing the operation
     * @param page the zero-based page index
     * @param event the Bukkit inventory event being handled
     * @return the validated inventory change proposal, or null if the action is rejected
     */
    @Nullable SharedInventoryTransaction click(Player player, int page, InventoryClickEvent event) {
        Draft draft = new Draft(player, page, event.getCursor());
        int topSize = event.getView().getTopInventory().getSize();
        int raw = event.getRawSlot();
        ClickType click = event.getClick();
        if (raw < 0) return null;
        boolean top = raw < topSize;
        if (!top && click != ClickType.SHIFT_LEFT && click != ClickType.SHIFT_RIGHT
                && click != ClickType.DOUBLE_CLICK) return null;
        int storageSlot = top ? owner.storageIndex(page, raw) : -1;
        if (top && storageSlot < 0) return null;

        if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
            if (top) {
                draft.action = SharedOperation.SHIFT_WITHDRAW;
                draft.withdrawToPlayer(storageSlot);
            } else {
                int source = event.getSlot();
                if (source < 0 || source >= player.getInventory().getStorageContents().length) return null;
                draft.action = SharedOperation.SHIFT_DEPOSIT;
                draft.depositFromPlayer(source);
            }
        } else if (click == ClickType.LEFT || click == ClickType.RIGHT) {
            draft.action = SharedOperation.CURSOR;
            draft.cursorClick(storageSlot, click == ClickType.RIGHT);
        } else if (click == ClickType.NUMBER_KEY) {
            draft.action = SharedOperation.HOTBAR_SWAP;
            int hotbar = event.getHotbarButton();
            if (hotbar >= 0 && hotbar < 9) draft.swapPlayerSlot(storageSlot, hotbar);
        } else if (click == ClickType.SWAP_OFFHAND) {
            draft.action = SharedOperation.OFFHAND_SWAP;
            draft.swapPlayerSlot(storageSlot, 40);
        } else if (click == ClickType.DOUBLE_CLICK) {
            draft.action = SharedOperation.COLLECT;
            draft.collectMatchingFromStorage();
        } else if (click == ClickType.DROP || click == ClickType.CONTROL_DROP) {
            // World drops are not a reversible transaction; fail closed instead.
            return null;
        } else {
            return null;
        }
        return draft.finish();
    }

    /**
     * Simulates an entire drag as one atomic operation, validating every destination.
     *
     * @param player the player performing the operation
     * @param page the zero-based page index
     * @param event the Bukkit inventory event being handled
     * @return the validated inventory change proposal, or null if the action is rejected
     */
    @Nullable SharedInventoryTransaction drag(Player player, int page, InventoryDragEvent event) {
        Draft draft = new Draft(player, page, event.getOldCursor());
        draft.action = SharedOperation.DRAG;
        int topSize = event.getView().getTopInventory().getSize();
        int before = 0;
        int after = 0;
        ItemStack initial = event.getOldCursor();
        if (empty(initial)) return null;
        for (Map.Entry<Integer, ItemStack> entry : event.getNewItems().entrySet()) {
            int raw = entry.getKey();
            ItemStack next = entry.getValue();
            if (raw < 0 || raw >= event.getView().countSlots() || !valid(next)) return null;
            ItemStack previous;
            if (raw < topSize) {
                int absolute = owner.storageIndex(page, raw);
                if (absolute < 0) return null;
                previous = draft.storage(absolute);
                if (!empty(previous) && !previous.isSimilar(next)) return null;
                if (!next.isSimilar(initial)) return null;
                draft.setStorage(absolute, next);
            } else {
                int playerSlot = event.getView().convertSlot(raw);
                if (playerSlot < 0 || playerSlot >= player.getInventory().getStorageContents().length)
                    return null;
                previous = draft.playerSlot(playerSlot);
                if (!empty(previous) && !previous.isSimilar(next)) return null;
                if (!next.isSimilar(initial)) return null;
                draft.setPlayerSlot(playerSlot, next);
            }
            int delta = amount(next) - amount(previous);
            if (delta < 0) return null;
            after += delta;
        }
        ItemStack cursor = event.getCursor();
        if (!empty(cursor) && !initial.isSimilar(cursor)) return null;
        before = amount(initial) - amount(cursor);
        if (before != after || before <= 0) return null;
        draft.cursor = copy(cursor);
        return draft.finish();
    }

    /** Returns an immutable proposal assembled from detached stack snapshots. */
    private final class Draft {
        private final Player player;
        private final int page;
        private final ItemStack originalCursor;
        private ItemStack cursor;
        private SharedOperation action;
        private final Map<Integer, ItemStack> storageAfter = new LinkedHashMap<>();
        private final Map<Integer, ItemStack> playerBefore = new LinkedHashMap<>();
        private final Map<Integer, ItemStack> playerAfter = new LinkedHashMap<>();

        private Draft(Player player, int page, ItemStack cursor) {
            this.player = player;
            this.page = page;
            this.cursor = copy(cursor);
            this.originalCursor = copy(cursor);
        }

        private ItemStack storage(int absolute) {
            if (storageAfter.containsKey(absolute)) return copy(storageAfter.get(absolute));
            return owner.readStorageItem(absolute);
        }

        private void setStorage(int absolute, ItemStack item) {
            storageAfter.put(absolute, copy(item));
        }

        private ItemStack playerSlot(int index) {
            if (playerAfter.containsKey(index)) return copy(playerAfter.get(index));
            if (index == 40) return copy(player.getInventory().getItemInOffHand());
            return copy(player.getInventory().getItem(index));
        }

        private void setPlayerSlot(int index, ItemStack value) {
            // A null snapshot is meaningful: the destination must still be empty when
            // SQL finishes. computeIfAbsent silently drops null and would allow overwrites.
            if (!playerBefore.containsKey(index)) {
                ItemStack original = index == 40 ? player.getInventory().getItemInOffHand()
                        : player.getInventory().getItem(index);
                playerBefore.put(index, copy(original));
            }
            playerAfter.put(index, copy(value));
        }

        /**
         * Applies native left/right click semantics to an isolated proposal.
         *
         * @param absolute the zero-based stable storage slot index
         * @param right whether the click uses the right mouse button
         */
        private void cursorClick(int absolute, boolean right) {
            ItemStack current = storage(absolute);
            if (empty(cursor)) {
                if (empty(current)) return;
                int taken = right ? (current.getAmount() + 1) / 2 : current.getAmount();
                cursor = copyWithAmount(current, taken);
                setStorage(absolute, subtract(current, taken));
                return;
            }
            if (empty(current)) {
                int moved = right ? 1 : Math.min(cursor.getAmount(), cursor.getMaxStackSize());
                setStorage(absolute, copyWithAmount(cursor, moved));
                cursor = subtract(cursor, moved);
                return;
            }
            if (current.isSimilar(cursor)) {
                int moved = Math.min(Math.max(0, current.getMaxStackSize() - current.getAmount()),
                        right ? 1 : cursor.getAmount());
                if (moved > 0) {
                    setStorage(absolute, copyWithAmount(current, current.getAmount() + moved));
                    cursor = subtract(cursor, moved);
                }
                return;
            }
            if (!right && cursor.getAmount() <= cursor.getMaxStackSize()) {
                setStorage(absolute, cursor);
                cursor = current;
            }
        }

        /**
         * Swaps one storage slot and one player slot without dropping overflow.
         *
         * @param absolute the zero-based stable storage slot index
         * @param slot the slot index associated with the operation
         */
        private void swapPlayerSlot(int absolute, int slot) {
            ItemStack fromPlayer = playerSlot(slot);
            ItemStack fromStorage = storage(absolute);
            if (!empty(fromPlayer) && !valid(fromPlayer)) return;
            setStorage(absolute, fromPlayer);
            setPlayerSlot(slot, fromStorage);
        }

        /**
         * Deposits a shift-clicked stack, searching stacks before empty slots.
         *
         * @param slot the slot index associated with the operation
         */
        private void depositFromPlayer(int slot) {
            ItemStack remaining = playerSlot(slot);
            if (empty(remaining)) return;
            int start = owner.transferMode() == SharedTransferMode.ALL_PAGES ? 0 : page;
            int end = owner.transferMode() == SharedTransferMode.ALL_PAGES ? owner.getSharedPages() : page + 1;
            for (int pass = 0; pass < 2 && !empty(remaining); pass++) {
                for (int p = start; p < end && !empty(remaining); p++) {
                    for (int visual : owner.fillSlots()) {
                        if (empty(remaining)) break;
                        int index = owner.storageIndex(p, visual);
                        ItemStack existing = storage(index);
                        if (pass == 0 && (empty(existing) || !existing.isSimilar(remaining))) continue;
                        if (pass == 1 && !empty(existing)) continue;
                        int room = empty(existing) ? remaining.getMaxStackSize() :
                                Math.max(0, existing.getMaxStackSize() - existing.getAmount());
                        int moved = Math.min(room, remaining.getAmount());
                        if (moved == 0) continue;
                        setStorage(index, empty(existing) ? copyWithAmount(remaining, moved)
                                : copyWithAmount(existing, existing.getAmount() + moved));
                        remaining = subtract(remaining, moved);
                    }
                }
            }
            setPlayerSlot(slot, remaining);
        }

        /**
         * Withdraws as much as fits in the player's standard 36-slot storage, retaining overflow.
         *
         * @param absolute the zero-based stable storage slot index
         */
        private void withdrawToPlayer(int absolute) {
            ItemStack remaining = storage(absolute);
            if (empty(remaining)) return;
            for (int pass = 0; pass < 2 && !empty(remaining); pass++) {
                for (int slot = 0; slot < 36 && !empty(remaining); slot++) {
                    ItemStack existing = playerSlot(slot);
                    if (pass == 0 && (empty(existing) || !existing.isSimilar(remaining))) continue;
                    if (pass == 1 && !empty(existing)) continue;
                    int room = empty(existing) ? remaining.getMaxStackSize()
                            : Math.max(0, existing.getMaxStackSize() - existing.getAmount());
                    int moved = Math.min(room, remaining.getAmount());
                    if (moved == 0) continue;
                    setPlayerSlot(slot, empty(existing) ? copyWithAmount(remaining, moved)
                            : copyWithAmount(existing, existing.getAmount() + moved));
                    remaining = subtract(remaining, moved);
                }
            }
            setStorage(absolute, remaining);
        }

        /** Gathers similar items from the active storage page into the cursor. */
        private void collectMatchingFromStorage() {
            if (empty(cursor)) return;
            for (int visual : owner.fillSlots()) {
                int absolute = owner.storageIndex(page, visual);
                ItemStack source = storage(absolute);
                if (empty(source) || !source.isSimilar(cursor)) continue;
                int taken = Math.min(source.getAmount(), cursor.getMaxStackSize() - cursor.getAmount());
                if (taken <= 0) break;
                cursor = copyWithAmount(cursor, cursor.getAmount() + taken);
                setStorage(absolute, subtract(source, taken));
            }
        }

        private SharedInventoryTransaction finish() {
            List<SharedItemChange> changes = new ArrayList<>();
            for (Map.Entry<Integer, ItemStack> entry : storageAfter.entrySet()) {
                int index = entry.getKey();
                ItemStack old = owner.readStorageItem(index);
                if (same(old, entry.getValue())) continue;
                changes.add(new SharedItemChange(index / owner.itemsPerPage(), owner.visualSlot(index), index,
                        old, entry.getValue()));
            }
            playerAfter.entrySet().removeIf(entry -> same(playerBefore.get(entry.getKey()), entry.getValue()));
            playerBefore.keySet().retainAll(playerAfter.keySet());
            if (changes.isEmpty()) return null;
            return new SharedInventoryTransaction(UUID.randomUUID(), owner.storageId(), player.getUniqueId(),
                    player.getName(), action, owner.revision(), changes, playerBefore, playerAfter,
                    originalCursor, cursor);
        }
    }

    /**
     * Compares item value and quantity, not object identity.
     *
     * @param left the first item stack to compare
     * @param right whether the click uses the right mouse button
     * @return true if same; false otherwise
     */
    static boolean same(@Nullable ItemStack left, @Nullable ItemStack right) {
        if (empty(left)) return empty(right);
        return !empty(right) && left.equals(right);
    }

    /**
     * Returns whether an item has no stored contents.
     *
     * @param item the item stack involved in the operation
     * @return true if empty; false otherwise
     */
    static boolean empty(@Nullable ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    /**
     * Returns whether a stack is a valid native storage value.
     *
     * @param item the item stack involved in the operation
     * @return true if valid; false otherwise
     */
    static boolean valid(@Nullable ItemStack item) {
        return empty(item) || (item.getAmount() > 0 && item.getAmount() <= item.getMaxStackSize()
                && item.getType() != Material.AIR);
    }

    /**
     * Copies a mutable Bukkit item, normalizing empty representations to null.
     *
     * @param item the item stack involved in the operation
     * @return a detached item stack copy
     */
    @Nullable static ItemStack copy(@Nullable ItemStack item) { return empty(item) ? null : item.clone(); }

    /**
     * Copies a stack and sets its strictly positive quantity.
     *
     * @param item the item stack involved in the operation
     * @param amount the number of items involved in the operation
     * @return a detached item stack copy
     */
    private static ItemStack copyWithAmount(ItemStack item, int amount) {
        ItemStack result = item.clone();
        result.setAmount(amount);
        return result;
    }

    /**
     * Subtracts a quantity without ever producing negative item amounts.
     *
     * @param item the item stack involved in the operation
     * @param amount the number of items involved in the operation
     * @return a copy containing the remaining item quantity, or null if empty
     */
    @Nullable private static ItemStack subtract(ItemStack item, int amount) {
        if (item.getAmount() <= amount) return null;
        return copyWithAmount(item, item.getAmount() - amount);
    }

    /**
     * Returns normalized stack count.
     *
     * @param item the item stack involved in the operation
     * @return the number of items in the stack
     */
    private static int amount(@Nullable ItemStack item) { return empty(item) ? 0 : item.getAmount(); }
}
