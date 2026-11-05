package org.broken.arrow.library.menu.shared.event;

import org.broken.arrow.library.menu.shared.SharedInventoryTransaction;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Synchronous cancellable event raised before a shared storage transaction is submitted. */
public final class SharedInventoryPreChangeEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final SharedInventoryTransaction transaction;
    private boolean cancelled;

    /**
     * Creates an event with detached transaction snapshots.
     *
     * @param transaction the immutable transaction describing the proposed or committed changes
     */
    public SharedInventoryPreChangeEvent(SharedInventoryTransaction transaction) { this.transaction = transaction; }
    /**
     * Returns the proposed transaction.
     *
     * @return the immutable transaction associated with the event
     */
    public SharedInventoryTransaction transaction() { return transaction; }
    /**
     * Returns whether this proposal was rejected.
     *
     * @return true if the proposed transaction has been cancelled
     */
    @Override public boolean isCancelled() { return cancelled; }
    /**
     * Rejects or permits the proposal.
     *
     * @param cancelled whether the proposed change has been cancelled
     */
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    /**
     * Returns event handlers.
     *
     * @return the handlers
     */
    @Override public HandlerList getHandlers() { return HANDLERS; }
    /**
     * Returns all registered handlers.
     *
     * @return the handler list
     */
    public static HandlerList getHandlerList() { return HANDLERS; }
}
