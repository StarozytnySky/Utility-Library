package org.broken.arrow.library.menu.shared;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Optional two-phase durability boundary for shared inventories. The library sends a unique
 * transaction through prepare -&gt; commit -&gt; delivered. The caller must implement idempotency,
 * a durable receipt for unresolved playerdata transfers and manual recovery of ambiguous outcomes.
 * <p>
 * After prepare succeeds, inputs are escrowed from the player's inventory/cursor before commit.
 * After commit succeeds, the library publishes the result and calls delivered. A failure after
 * escrow causes a fail-closed quarantine instead of any automatic item repayment.
 * </p>
 */
public interface SharedInventoryPersistence {
    /**
     * Persists an operation intent durably before any source item is taken into escrow.
     *
     * @param transaction the immutable transaction describing the proposed or committed changes
     * @return a completion stage that finishes once the operation intent is durable
     */
    default CompletionStage<Void> prepare(SharedInventoryTransaction transaction) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Atomically persists all destination changes; do not complete until commit is durable.
     *
     * @param transaction the immutable transaction describing the proposed or committed changes
     * @return a completion stage that finishes once the storage changes are committed
     */
    CompletionStage<Void> commit(SharedInventoryTransaction transaction);

    /**
     * Acknowledges successful player delivery. Failure must preserve a reviewable receipt.
     *
     * @param transaction the immutable transaction describing the proposed or committed changes
     * @return a completion stage that finishes once the delivery receipt is recorded
     */
    default CompletionStage<Void> delivered(SharedInventoryTransaction transaction) {
        return CompletableFuture.completedFuture(null);
    }
}
