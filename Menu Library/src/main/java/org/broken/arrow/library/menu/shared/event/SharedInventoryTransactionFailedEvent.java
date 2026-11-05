package org.broken.arrow.library.menu.shared.event;

import org.broken.arrow.library.menu.shared.SharedInventoryTransaction;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Synchronous notification of a failed or ambiguous shared inventory transaction. */
public final class SharedInventoryTransactionFailedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final SharedInventoryTransaction transaction;
    private final Throwable cause;

    /**
     * Captures a failed operation and its cause.
     *
     * @param transaction the immutable transaction describing the proposed or committed changes
     * @param cause the cause of the inventory operation
     */
    public SharedInventoryTransactionFailedEvent(SharedInventoryTransaction transaction, Throwable cause) {
        this.transaction = transaction;
        this.cause = cause;
    }
    /**
     * Returns the attempted transaction.
     *
     * @return the immutable transaction associated with the event
     */
    public SharedInventoryTransaction transaction() { return transaction; }
    /**
     * Returns the reason for the failure.
     *
     * @return the throwable that caused the transaction to fail
     */
    public Throwable cause() { return cause; }
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
