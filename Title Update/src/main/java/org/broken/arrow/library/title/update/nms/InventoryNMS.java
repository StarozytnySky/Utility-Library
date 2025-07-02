package org.broken.arrow.library.title.update.nms;

import org.bukkit.inventory.Inventory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * This class handle the inventory methods needed to access current inventory player open.
 */
public interface InventoryNMS extends NMSInitializer {


	/**
	 * The name of the field inside net.minecraft.world.entity.player.EntityHuman
	 * that corresponds to the current inventory container set.
	 *
	 * @return The name of the container field.
	 */
	@Nonnull
	String getContainerField();

	/**
	 * Retrieves the field name that represents the window ID of the current inventory.
	 * This ID is an integer value.
	 * <p>
	 * The field in this class net.minecraft.world.entity.player.EntityHuman.
	 *
	 * @return The name of the window ID field.
	 */
	@Nonnull
	String getWindowId();

	/**
	 * Retrieves the method name responsible for sending a packet.
	 * In older versions, this method was named sendPacket(Packet packet)
	 * and newer version is it a(Packet packet).
	 *
	 * @return The name of the method for sending a packet.
	 */
	@Nonnull
	String getSendPacketName();

	/**
	 * Retrieves the method name used to update an inventory.
	 * In older versions, this method was named initMenu or updateInventory.
	 * <p>
	 * The field is from this class net.minecraft.world.inventory.Container.
	 * </p>
	 *
	 * @return The name of the method for updating the inventory.
	 */
	@Nonnull
	String getUpdateInventoryMethodName();

	/**
	 * Retrieves the appropriate name for the container type field associated with the
	 * inventory that is currently open for the provided player.
	 *
	 * @param currentlyOpenInventory The inventory that the player currently has open.
	 * @return The name of the field corresponding to the player's currently open inventory.
	 */
	@Nullable
	String getContainerFieldName(@Nonnull final Inventory currentlyOpenInventory);

}
