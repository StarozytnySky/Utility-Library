package org.broken.arrow.library.menu.button.logic;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public interface FillSlotInteractionHandler<T> {
	/**
	 * When player add item
	 * @param player the player who added the item
	 * @param slot the slot where the item was added
	 * @param item the item that was added
	 * @param itemStack the item stack that was added
	 */
	default void onItemAdded(Player player, int slot, T item, ItemStack itemStack) {
	}

	/**
	 * When player remove item
	 * @param player the player who removed the item
	 * @param slot the slot where the item was removed
	 * @param item the item that was removed
	 * @param itemStack the item stack that was removed
	 */
	default void onItemRemoved(Player player, int slot, T item, ItemStack itemStack){
	}

	/**
	 * When player click item
	 * @param player the player who clicked the item
	 * @param menu the inventory menu
	 * @param clickType the type of click
	 * @param clickedItem the item that was clicked
	 * @param fillObject the object associated with the filled slot
	 */
	default void onItemClick(Player player, Inventory menu, ClickType clickType, ItemStack clickedItem, T fillObject){
	}
}
