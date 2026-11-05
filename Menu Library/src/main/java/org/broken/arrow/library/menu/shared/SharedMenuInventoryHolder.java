package org.broken.arrow.library.menu.shared;

import org.broken.arrow.library.menu.holder.MenuHolderShared;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import javax.annotation.Nonnull;
import java.util.UUID;

/** Identifies a particular page of a particular shared menu without player metadata. */
public final class SharedMenuInventoryHolder implements InventoryHolder {
    private final MenuHolderShared<?> owner;
    private final UUID storageId;
    private final int page;
    private Inventory inventory;

    /**
     * Creates the identity for an inventory page.
     *
     * @param owner the shared inventory menu that owns the controller
     * @param storageId the unique identity of the shared storage
     * @param page the zero-based page index
     */
    public SharedMenuInventoryHolder(MenuHolderShared<?> owner, UUID storageId, int page) {
        this.owner = owner;
        this.storageId = storageId;
        this.page = page;
    }
    /**
     * Attaches the Bukkit inventory exactly once.
     *
     * @param inventory the Bukkit inventory being used
     */
    public void attach(Inventory inventory) {
        if (this.inventory != null) throw new IllegalStateException("Shared inventory already attached");
        this.inventory = inventory;
    }
    /**
     * Gets the menu instance owning this page.
     *
     * @return the shared menu that owns this inventory holder
     */
    public MenuHolderShared<?> owner() { return owner; }
    /**
     * Gets the stable storage ID.
     *
     * @return the UUID uniquely identifying the shared storage
     */
    public UUID storageId() { return storageId; }
    /**
     * Gets the zero-based page.
     *
     * @return the zero-based page index
     */
    public int page() { return page; }
    /**
     * Gets the underlying shared Bukkit inventory.
     *
     * @return the inventory
     */
    @Override @Nonnull public Inventory getInventory() { return inventory; }
}
