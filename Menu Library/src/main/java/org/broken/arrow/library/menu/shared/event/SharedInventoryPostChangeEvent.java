package org.broken.arrow.library.menu.shared.event;

import org.broken.arrow.library.menu.shared.SharedInventoryTransaction;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Synchronous, informative event raised only after a transaction has been accepted and applied. */
public final class SharedInventoryPostChangeEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final SharedInventoryTransaction transaction;

    /**
     * Creates an event for one fully committed transaction.
     *
     * @param transaction the immutable transaction describing the proposed or committed changes
     */
    public SharedInventoryPostChangeEvent(SharedInventoryTransaction transaction) { this.transaction = transaction; }
    /**
     * Returns the immutable transaction details.
     *
     * @return the immutable transaction associated with the event
     */
    public SharedInventoryTransaction transaction() { return transaction; }
    /**
     * Returns event handlers.
     *
     * @return the handlers
     */
    @Override public HandlerList getHandlers() { return HANDLERS; }
    /**
     * Returns registered handlers.
     *
     * @return the handler list
     */
    public static HandlerList getHandlerList() { return HANDLERS; }
}
