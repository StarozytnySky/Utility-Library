package org.broken.arrow.library.menu.utility;

import org.bukkit.inventory.ItemStack;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Index-preserving storage for shared menus. Clearing a slot never shifts other slots.
 * ItemStack values are copied both on reads and writes to prevent aliasing with Bukkit inventories.
 * Arbitrary non-ItemStack types should be immutable or have their own conversion strategy.
 *
 * @param <T> item representation.
 */
public final class StableFillItems<T> {
    private final List<T> items = new ArrayList<>();

    /**
     * Creates detached storage from an optional list.
     *
     * @param initial the initial state of the slot or stack
     */
    public StableFillItems(@Nullable List<T> initial) { setFillItems(initial); }

    /**
     * Returns an immutable detached snapshot including null gaps.
     *
     * @return the fill items
     */
    public List<T> getFillItems() {
        List<T> result = new ArrayList<>(items.size());
        items.forEach(item -> result.add(detach(item)));
        return Collections.unmodifiableList(result);
    }

    /**
     * Returns the detached item at one stable index, or null for a hole.
     *
     * @param index the zero-based item or slot index
     * @return the fill item
     */
    @Nullable public T getFillItem(int index) {
        return index < 0 || index >= items.size() ? null : detach(items.get(index));
    }

    /**
     * Replaces one absolute slot while preserving all other indices.
     *
     * @param index the zero-based item or slot index
     * @param item the item stack involved in the operation
     */
    public void setAt(int index, @Nullable T item) {
        if (index < 0) throw new IndexOutOfBoundsException("Negative storage index");
        if (item == null && index >= items.size()) return;
        while (items.size() <= index) items.add(null);
        items.set(index, detach(item));
        if (item == null) trimTrailingNulls();
    }

    /**
     * Replaces contents while preserving existing null holes in the new snapshot.
     *
     * @param contents the initial collection of inventory contents
     */
    public void setFillItems(@Nullable List<T> contents) {
        List<T> copy = new ArrayList<>();
        if (contents != null) contents.forEach(item -> copy.add(detach(item)));
        items.clear();
        items.addAll(copy);
        trimTrailingNulls();
    }

    /**
     * Appends one new position without compacting gaps.
     *
     * @param item the item stack involved in the operation
     */
    public void addFillItem(@Nullable T item) { items.add(detach(item)); }

    /**
     * Removes one item without changing any other index.
     *
     * @param index the zero-based item or slot index
     */
    public void clearAt(int index) { setAt(index, null); }

    /** Clears all storage contents. */
    public void clear() { items.clear(); }

    /**
     * Returns the highest occupied index plus one, not the number of occupied slots.
     *
     * @return the number of stored items or slots
     */
    public int size() { return items.size(); }

    /**
     * Finds the earliest vacant slot, or the end of stored space.
     *
     * @return the index of the first empty storage slot, or the current size when full
     */
    public int firstEmptyIndex() {
        for (int index = 0; index < items.size(); index++) if (items.get(index) == null) return index;
        return items.size();
    }

    /** Trims only empty tail slots; interior holes are never moved. */
    private void trimTrailingNulls() {
        for (int index = items.size() - 1; index >= 0; index--) {
            if (items.get(index) != null) return;
            items.remove(index);
        }
    }

    /**
     * Copies Bukkit item stacks while leaving immutable application objects untouched.
     *
     * @param item the item stack involved in the operation
     * @return a defensive copy of the supplied value, or null when no value exists
     */
    @SuppressWarnings("unchecked")
    @Nullable private T detach(@Nullable T item) {
        return item instanceof ItemStack ? (T) ((ItemStack) item).clone() : item;
    }
}
