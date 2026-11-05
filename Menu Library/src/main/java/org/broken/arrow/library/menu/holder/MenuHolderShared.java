package org.broken.arrow.library.menu.holder;

import org.broken.arrow.library.menu.button.MenuButton;
import org.broken.arrow.library.menu.button.logic.FillMenuButton;
import org.broken.arrow.library.menu.shared.SharedInventoryPersistence;
import org.broken.arrow.library.menu.shared.SharedInventoryTransaction;
import org.broken.arrow.library.menu.shared.SharedOperation;
import org.broken.arrow.library.menu.shared.SharedItemChange;
import org.broken.arrow.library.menu.shared.SharedMenuInventoryHolder;
import org.broken.arrow.library.menu.shared.SharedPageMode;
import org.broken.arrow.library.menu.shared.SharedTransferMode;
import org.broken.arrow.library.menu.shared.event.SharedInventoryPreChangeEvent;
import org.broken.arrow.library.menu.shared.event.SharedInventoryPostChangeEvent;
import org.broken.arrow.library.menu.shared.event.SharedInventoryTransactionFailedEvent;
import org.broken.arrow.library.menu.utility.Action;
import org.broken.arrow.library.menu.utility.MetadataPlayer;
import org.broken.arrow.library.menu.utility.StableFillItems;
import org.broken.arrow.library.menu.utility.metadata.MenuMetadataKey;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Real shared Bukkit inventories with per-player page selection and index-stable storage.
 * Each physical page has exactly one Bukkit Inventory for all viewers. All mutations are
 * proposed and validated before being committed to the inventory on the server thread.
 * <p>
 * For persistence-sensitive inventories install {@link #setPersistence(SharedInventoryPersistence)}
 * before opening the first viewer. The persistence implementation is responsible for
 * durable intent and receipt management when transfers cross the Minecraft playerdata boundary.
 * </p>
 *
 * @param <T> stored representation (ItemStack is the normal use case).
 */
public class MenuHolderShared<T> extends HolderUtility<T> {
    private final UUID storageId = UUID.randomUUID();
    private final StableFillItems<T> stored;
    private final SharedInventoryController controller = new SharedInventoryController(this);
    private final Map<Integer, SharedMenuInventoryHolder> pages = new HashMap<>();
    private final Map<UUID, Integer> viewers = new HashMap<>();
    private final Set<UUID> switching = new HashSet<>();
    private final ThreadLocal<Player> renderingViewer = new ThreadLocal<>();
    private List<Integer> fillSlots = Collections.emptyList();
    private SharedPageMode pageMode = SharedPageMode.AUTO;
    private SharedTransferMode transferMode = SharedTransferMode.CURRENT_PAGE;
    private int fixedPages = 1;
    private int maxAutoPages = 128;
    private long revision;
    private boolean busy;
    private boolean quarantined;
    private SharedInventoryPersistence persistence;

    /** Creates an empty shared inventory. */
    protected MenuHolderShared() { this(null); }

    /**
     * Creates a shared inventory with detached initial contents.
     *
     * @param fillItems the initial stored items, or null for an empty storage
     */
    protected MenuHolderShared(@Nullable List<T> fillItems) {
        super(null, false);
        this.stored = new StableFillItems<>(fillItems);
        this.autoClearCache = false;
        this.autoTitleCurrentPage = false;
    }

    /**
     * Converts one stored value to a detached Bukkit item.
     *
     * @param fillItem the stored value to render as an item
     * @param absoluteIndex the zero-based stable storage slot index
     * @return the converted display item, or null if conversion is unavailable
     */
    @Nullable protected ItemStack toDisplayItem(@Nullable T fillItem, int absoluteIndex) {
        if (fillItem instanceof ItemStack) return ((ItemStack) fillItem).clone();
        FillMenuButton<T> converter = createFillMenuButton();
        if (converter == null) return null;
        ItemStack item = converter.getItemProvider().apply(absoluteIndex, fillItem);
        return SharedInventoryController.copy(item);
    }

    /**
     * Converts a Bukkit item into a detached stored value; null rejects the item.
     *
     * @param stack the item stack to convert into stored data
     * @return the converted stored item, or null if conversion is unavailable
     */
    @Nullable @SuppressWarnings("unchecked")
    protected T toStoredItem(@Nonnull ItemStack stack) { return (T) stack.clone(); }

    /**
     * Optional item renderer for shared menus with non-ItemStack data.
     *
     * @return the optional item converter, or null if the default conversion is used
     */
    @Nullable public FillMenuButton<T> createFillMenuButton() { return null; }

    /**
     * Optional legacy per-slot notification; invoked after committed changes only.
     *
     * @param player the player performing the operation
     * @param absoluteIndex the zero-based stable storage slot index
     * @param previous the item previously stored at the slot
     * @param current the item now stored at the slot
     */
    protected void onSharedItemsChanged(@Nonnull Player player, int absoluteIndex,
                                        @Nullable T previous, @Nullable T current) { }

    /**
     * Returns whether items may be moved at all.
     *
     * @return true if the operation is permitted; false otherwise
     */
    protected boolean canEditFillItems() { return true; }

    /**
     * Optional per-viewer page authorization hook, evaluated for every open and page switch.
     *
     * @param viewer the player viewing this shared inventory
     * @param page the zero-based page index
     * @return true if the operation is permitted; false otherwise
     */
    protected boolean canAccessPage(@Nonnull Player viewer, int page) { return true; }

    /**
     * Optional item insertion policy; invoked before any SQL transaction.
     *
     * @param viewer the player viewing this shared inventory
     * @param item the item stack involved in the operation
     * @param storageSlot the zero-based stable storage slot index
     * @return true if the operation is permitted; false otherwise
     */
    protected boolean canInsert(@Nonnull Player viewer, @Nonnull ItemStack item, int storageSlot) { return true; }

    /**
     * Optional item extraction policy; invoked before any SQL transaction.
     *
     * @param viewer the player viewing this shared inventory
     * @param item the item stack involved in the operation
     * @param storageSlot the zero-based stable storage slot index
     * @return true if the operation is permitted; false otherwise
     */
    protected boolean canExtract(@Nonnull Player viewer, @Nonnull ItemStack item, int storageSlot) { return true; }

    /**
     * Optional synchronous policy decision, before persistence is called.
     *
     * @param transaction the immutable transaction describing the proposed or committed changes
     * @return true to permit the proposed transaction; false to reject it
     */
    protected boolean onBeforeTransaction(@Nonnull SharedInventoryTransaction transaction) { return true; }

    /**
     * Optional notification for a committed transaction, including all modified slots.
     *
     * @param transaction the immutable transaction describing the proposed or committed changes
     */
    protected void onItemsChanged(@Nonnull SharedInventoryTransaction transaction) { }

    /**
     * Optional notification for a committed insertion into the storage.
     *
     * @param change the description of the committed slot change
     */
    protected void onItemDeposited(@Nonnull SharedItemChange change) { }

    /**
     * Optional notification for a committed extraction from the storage.
     *
     * @param change the description of the committed slot change
     */
    protected void onItemWithdrawn(@Nonnull SharedItemChange change) { }

    /**
     * Optional notification for a committed internal relocation or swap.
     *
     * @param change the description of the committed slot change
     */
    protected void onItemMoved(@Nonnull SharedItemChange change) { }

    /**
     * Optional error notification. A persistence failure quarantines the storage.
     *
     * @param transaction the immutable transaction describing the proposed or committed changes
     * @param error the exception that caused the transaction failure
     */
    protected void onTransactionFailed(@Nonnull SharedInventoryTransaction transaction, @Nonnull Throwable error) { }

    /**
     * Installs a durable persistence barrier; null means in-memory-only behavior.
     *
     * @param persistence the persistence adapter used to authorize durable transactions, or null
     */
    public final void setPersistence(@Nullable SharedInventoryPersistence persistence) {
        ensureConfigurable();
        this.persistence = persistence;
    }

    /**
     * Sets a strict page count, rejecting deposits beyond its capacity.
     *
     * @param count the number of pages to allocate
     */
    public final void fixedPages(int count) {
        ensureConfigurable();
        if (count <= 0) throw new IllegalArgumentException("Fixed page count must be positive");
        this.fixedPages = count;
        this.pageMode = SharedPageMode.FIXED;
    }

    /**
     * Enables automatic growth up to the specified positive limit.
     *
     * @param maxPages the maximum number of pages that may be allocated
     */
    public final void automaticPages(int maxPages) {
        ensureConfigurable();
        if (maxPages <= 0) throw new IllegalArgumentException("Maximum page count must be positive");
        this.maxAutoPages = maxPages;
        this.pageMode = SharedPageMode.AUTO;
    }

    /**
     * Chooses where quick-deposit searches for available slots.
     *
     * @param mode the selected transfer behavior
     */
    public final void transferMode(@Nonnull SharedTransferMode mode) {
        ensureConfigurable();
        this.transferMode = Objects.requireNonNull(mode);
    }

    /**
     * Returns the stable identity of this shared menu instance.
     *
     * @return the storage id
     */
    public final UUID getStorageId() { return storageId; }

    /**
     * Current storage revision, incremented once per committed transaction.
     *
     * @return the revision
     */
    public final long getRevision() { return revision; }

    /**
     * Returns whether the menu is locked after an ambiguous persistence outcome.
     *
     * @return true if the storage is quarantined after an ambiguous persistence failure
     */
    public final boolean isQuarantined() { return quarantined; }

    /**
     * Returns whether a transfer is currently being prepared or committed.
     *
     * @return true when has pending transaction; false otherwise
     */
    public final boolean hasPendingTransaction() { return busy; }

    /**
     * Returns the exact number of active viewers.
     *
     * @return the number of players currently viewing the shared storage
     */
    public final int viewerCount() { return viewers.size(); }

    /**
     * Optional notification when one viewer closes this storage (not on page switches).
     *
     * @param viewer the player viewing this shared inventory
     */
    protected void onViewerClosed(@Nonnull Player viewer) { }

    /** Optional notification when the last player leaves the shared storage. */
    protected void onLastViewerClosed() { }

    /** Closes all connected viewers; useful when storage capacity changes or a plugin shuts down. */
    public final void closeAllViewers() {
        requireMainThread();
        for (UUID id : new ArrayList<>(viewers.keySet())) {
            Player online = Bukkit.getPlayer(id);
            if (online != null) online.closeInventory();
            else viewers.remove(id);
        }
    }

    /**
     * Opens a specific zero-based page for one viewer.
     *
     * @param viewer the player viewing this shared inventory
     * @param page the zero-based page index
     */
    public final void menuOpen(@Nonnull Player viewer, int page) {
        requireMainThread();
        if (quarantined || busy) return;
        configureSlots();
        validateCapacity();
        if (page < 0 || page >= getSharedPages()) throw new IndexOutOfBoundsException("Invalid shared page: " + page);
        if (!canAccessPage(viewer, page)) return;
        openPage(viewer, page);
    }

    /**
     * Proposes an atomic relocation or swap of two stored slots without touching the actor's
     * inventory. The operation is committed through the same policy and persistence pipeline
     * as player clicks. The return value means submission was accepted, not SQL completion.
     *
     * @param actor the player initiating the operation
     * @param sourcePage the zero-based source page index
     * @param sourceSlot the slot containing the source item
     * @param targetPage the zero-based destination page index
     * @param targetSlot the destination slot for the item
     * @return true if move stored item; false otherwise
     */
    public final boolean moveStoredItem(@Nonnull Player actor, int sourcePage, int sourceSlot,
                                        int targetPage, int targetSlot) {
        requireMainThread();
        if (busy || quarantined || !canEditFillItems()) return false;
        configureSlots();
        if (!currentView(actor, actor.getOpenInventory().getTopInventory())
                || sourcePage < 0 || sourcePage >= getSharedPages()
                || targetPage < 0 || targetPage >= getSharedPages()
                || !canAccessPage(actor, sourcePage) || !canAccessPage(actor, targetPage)) return false;
        int sourceIndex = storageIndex(sourcePage, sourceSlot);
        int targetIndex = storageIndex(targetPage, targetSlot);
        if (sourceIndex < 0 || targetIndex < 0 || sourceIndex == targetIndex) return false;
        ItemStack source = readStorageItem(sourceIndex);
        ItemStack target = readStorageItem(targetIndex);
        if (SharedInventoryController.empty(source) || SharedInventoryController.same(source, target)) return false;
        List<SharedItemChange> changes = Arrays.asList(
                new SharedItemChange(sourcePage, sourceSlot, sourceIndex, source, target),
                new SharedItemChange(targetPage, targetSlot, targetIndex, target, source));
        ItemStack cursor = actor.getOpenInventory().getCursor();
        SharedInventoryTransaction proposal = new SharedInventoryTransaction(UUID.randomUUID(), storageId,
                actor.getUniqueId(), actor.getName(), SharedOperation.PROGRAMMATIC, revision, changes,
                Collections.emptyMap(), Collections.emptyMap(), cursor, cursor);
        return submit(actor, actor.getOpenInventory().getTopInventory(), proposal);
    }

    /**
     * Returns a detached snapshot wrapper; mutating it does not change the active shared storage.
     *
     * @return an independent snapshot of the stored slot contents
     */
    @Nonnull public StableFillItems<T> getListOfFillItem() { return new StableFillItems<>(stored.getFillItems()); }

    /**
     * Returns a detached immutable snapshot including null holes.
     *
     * @return an independent snapshot of the stored slot contents
     */
    @Override @Nonnull public List<T> getListOfFillItems() { return stored.getFillItems(); }

    /**
     * Returns the stored item at an absolute stable storage index.
     *
     * @param index the zero-based item or slot index
     * @return the fill item
     */
    @Nullable public T getFillItem(int index) { return stored.getFillItem(index); }

    /**
     * Replaces initial contents before viewers connect. Durable stores must load through the
     * constructor instead: replacing contents outside SQL receipts is not a valid mutation.
     *
     * @param items the replacement stored items in stable slot order
     */
    public final void setSharedFillItems(@Nullable List<T> items) {
        requireMainThread();
        ensureConfigurable();
        configureSlots();
        if (persistence != null || quarantined)
            throw new IllegalStateException("Persistent shared storage cannot be overwritten outside transactions");
        StableFillItems<T> candidate = new StableFillItems<>(items);
        int maxCapacity = (pageMode == SharedPageMode.FIXED ? fixedPages : maxAutoPages) * itemsPerPage();
        if (candidate.size() > maxCapacity) throw new IllegalArgumentException("Storage exceeds configured maximum pages");
        stored.setFillItems(candidate.getFillItems());
        revision++;
        pages.clear();
    }

    /**
     * Returns the currently available number of pages.
     *
     * @return the shared pages
     */
    public final int getSharedPages() {
        int perPage = itemsPerPage();
        if (pageMode == SharedPageMode.FIXED) return fixedPages;
        int count = Math.max(1, (stored.size() + perPage - 1) / perPage);
        if (stored.size() > 0 && stored.size() % perPage == 0) count++;
        return Math.min(maxAutoPages, count);
    }

    /**
     * Returns the amount of pages for the inherited menu API.
     *
     * @return the required pages
     */
    @Override public int getRequiredPages() { return getSharedPages(); }

    /**
     * Returns the zero-based page of the bound viewer.
     *
     * @return the page number
     */
    @Override public int getPageNumber() {
        Player context = renderingViewer.get();
        if (context == null) context = player;
        if (context != null) return viewers.getOrDefault(context.getUniqueId(), 0);
        return 0;
    }

    /**
     * Returns the current viewer when building page-dependent titles.
     *
     * @return the viewer
     */
    @Override public Player getViewer() {
        Player context = renderingViewer.get();
        return context == null ? super.getViewer() : context;
    }

    /**
     * Binds a viewer for legacy button callbacks without touching other players' inventories.
     *
     * @param viewer the player viewing this shared inventory
     */
    @Override public void bindViewer(@Nonnull Player viewer) {
        super.bindViewer(viewer);
        Integer page = viewers.get(viewer.getUniqueId());
        if (page != null && pages.containsKey(page)) setMenu(pages.get(page).getInventory());
    }

    /**
     * Opens the same page inventory for every viewer of that page.
     *
     * @param viewer the player viewing this shared inventory
     * @param location the location associated with the inventory view
     * @param loadToCache whether the opened view should be cached
     */
    @Override public void menuOpen(@Nonnull Player viewer, @Nullable Location location, boolean loadToCache) {
        requireMainThread();
        if (quarantined) return;
        configureSlots();
        validateCapacity();
        int page = Math.min(viewers.getOrDefault(viewer.getUniqueId(), 0), getSharedPages() - 1);
        if (!canAccessPage(viewer, page)) return;
        openPage(viewer, page);
    }

    /**
     * Checks the actual Bukkit holder identity instead of accepting stale player metadata.
     *
     * @param top the top inventory in the current inventory view
     * @param action the action or reason for the inventory change
     * @return true if check valid menu; false otherwise
     */
    @Override public boolean checkValidMenu(@Nonnull Inventory top, @Nonnull Action action) {
        if (!(top.getHolder() instanceof SharedMenuInventoryHolder)) return false;
        SharedMenuInventoryHolder holder = (SharedMenuInventoryHolder) top.getHolder();
        return holder.owner() == this && holder.getInventory() == top && holder.storageId().equals(storageId)
                && pages.get(holder.page()) == holder;
    }

    /**
     * Returns the number of other viewers of this shared storage.
     *
     * @return the amount of viewers
     */
    @Override public int getAmountOfViewers() { return Math.max(0, viewers.size() - 1); }

    /**
     * Optional close hook independent of the deprecated two-argument menu-close callback.
     * Per-viewer cleanup is managed by {@link #unregister(Player)}.
     *
     * @param event the Bukkit inventory event being handled
     */
    @Override public void menuClose(@Nonnull InventoryCloseEvent event) { }

    /**
     * Leaves shared storage and detaches one viewer without affecting others.
     *
     * @param viewer the player viewing this shared inventory
     */
    @Override public void unregister(@Nonnull Player viewer) {
        UUID id = viewer.getUniqueId();
        if (switching.contains(id)) return;
        viewers.remove(id);
        safeCallback(() -> onViewerClosed(viewer));
        MetadataPlayer metadata = menuAPI.getPlayerMeta();
        if (metadata.hasPlayerMetadata(viewer, MenuMetadataKey.MENU_OPEN)
                && metadata.getPlayerMenuMetadata(viewer, MenuMetadataKey.MENU_OPEN) == this)
            metadata.removePlayerMenuMetadata(viewer, MenuMetadataKey.MENU_OPEN);
        if (viewers.isEmpty() && !busy) {
            pages.clear();
            closeTasks();
            onLastViewerClosed();
        }
    }

    /**
     * Changes only the active viewer's page, opening the target inventory on the next tick.
     *
     * @param page the zero-based page index
     * @return true if set page; false otherwise
     */
    @Override public boolean setPage(int page) { return player != null && setPage(player, page); }

    /**
     * Requests an independent page change for the specified player.
     *
     * @param viewer the player viewing this shared inventory
     * @param page the zero-based page index
     * @return true if set page; false otherwise
     */
    public boolean setPage(@Nonnull Player viewer, int page) {
        requireMainThread();
        if (page < 0 || page >= getSharedPages() || !viewers.containsKey(viewer.getUniqueId()) || busy) return false;
        Bukkit.getScheduler().runTask(menuAPI.getPlugin(), () -> {
            if (viewer.isOnline() && !quarantined && !busy && viewers.containsKey(viewer.getUniqueId())
                    && page < getSharedPages() && canAccessPage(viewer, page)) openPage(viewer, page);
        });
        return true;
    }

    /**
     * Wraps from the last page to the first, independently for each viewer.
     *
     * @param next the next page index
     */
    @Override protected void changePage(boolean next) {
        if (player == null) return;
        int count = getSharedPages();
        setPage(player, Math.floorMod(getPageNumber() + (next ? 1 : -1), count));
    }

    /**
     * Updates a single viewer's title in the appropriate page context.
     *
     * @param viewer the player viewing this shared inventory
     */
    @Override public void updateTitle(@Nullable Player viewer) {
        if (viewer == null || !viewers.containsKey(viewer.getUniqueId())) return;
        renderingViewer.set(viewer);
        try { super.updateTitle(viewer); }
        finally { renderingViewer.remove(); }
    }

    /**
     * Routes only events belonging to this exact inventory and viewer.
     *
     * @param event the Bukkit inventory event being handled
     */
    @Override public void onInventoryClick(@Nonnull InventoryClickEvent event) { handleClick(event); }

    /**
     * Routes drag operations through the same atomic commit path.
     *
     * @param event the Bukkit inventory event being handled
     */
    @Override public void onInventoryDrag(@Nonnull InventoryDragEvent event) { handleDrag(event); }

    /**
     * Dispatches one native click; always cancels modifications to protected slots.
     *
     * @param event the Bukkit inventory event being handled
     */
    public final void handleClick(@Nonnull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player viewer = (Player) event.getWhoClicked();
        if (!currentView(viewer, event.getView().getTopInventory())) return;
        int topSize = event.getView().getTopInventory().getSize();
        int raw = event.getRawSlot();
        boolean top = raw >= 0 && raw < topSize;
        if (top && !isFillSlot(raw)) {
            event.setCancelled(true);
            if (busy || quarantined) return;
            MenuButton button = getButtonAt(raw);
            if (button != null) {
                bindViewer(viewer);
                ItemStack item = event.getCurrentItem();
                onClick(button, viewer, raw, event.getClick(), item == null ? new ItemStack(org.bukkit.Material.AIR) : item.clone());
            }
            return;
        }
        if (busy || quarantined) {
            event.setCancelled(true);
            return;
        }
        if (!top && !event.isShiftClick() && event.getClick() != org.bukkit.event.inventory.ClickType.DOUBLE_CLICK)
            return;
        event.setCancelled(true);
        if (!canEditFillItems()) return;
        SharedInventoryTransaction proposal = controller.click(viewer, viewers.get(viewer.getUniqueId()), event);
        if (proposal != null) submit(viewer, event.getView().getTopInventory(), proposal);
    }

    /**
     * Dispatches one native drag after pre-validating every top/bottom destination.
     *
     * @param event the Bukkit inventory event being handled
     */
    public final void handleDrag(@Nonnull InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player viewer = (Player) event.getWhoClicked();
        if (!currentView(viewer, event.getView().getTopInventory())) return;
        if (busy || quarantined || !canEditFillItems()) {
            event.setCancelled(true);
            return;
        }
        boolean touchesTop = event.getRawSlots().stream().anyMatch(raw -> raw < event.getView().getTopInventory().getSize());
        if (!touchesTop) return;
        event.setCancelled(true);
        SharedInventoryTransaction proposal = controller.drag(viewer, viewers.get(viewer.getUniqueId()), event);
        if (proposal != null) submit(viewer, event.getView().getTopInventory(), proposal);
    }

    /**
     * Moves a proposal through durable intent, source escrow and atomic commit. The inventory
     * remains locked throughout the operation. The plain LIVE mode uses the same simulator but
     * does not escrow or quarantine transactions that were never persisted.
     *
     * @param viewer the player viewing this shared inventory
     * @param sourceInventory the source inventory containing the actor items
     * @param proposal the proposed changes to validate or execute
     * @return true if submit; false otherwise
     */
    private boolean submit(Player viewer, Inventory sourceInventory, SharedInventoryTransaction proposal) {
        if (busy || quarantined || proposal.expectedRevision() != revision || !validateProposal(proposal)) return false;
        try {
            if (!checkItemPolicies(viewer, proposal) || !onBeforeTransaction(proposal)) return false;
            SharedInventoryPreChangeEvent preEvent = new SharedInventoryPreChangeEvent(proposal);
            Bukkit.getPluginManager().callEvent(preEvent);
            if (preEvent.isCancelled()) return false;
        } catch (RuntimeException failure) {
            menuAPI.getPlugin().getLogger().log(Level.SEVERE, "Shared inventory policy failed", failure);
            return false;
        }
        busy = true;
        if (persistence == null) {
            // Avoid modifying inventory or cursor while the click event is executing.
            Bukkit.getScheduler().runTask(menuAPI.getPlugin(), () -> {
                try {
                    if (viewer.isOnline() && currentView(viewer, sourceInventory) && validateProposal(proposal)
                            && matchesPlayerState(viewer, proposal)) apply(viewer, proposal);
                } catch (RuntimeException failure) {
                    transactionFailed(proposal, failure);
                    return;
                }
                finishTransaction();
            });
            return true;
        }
        CompletionStage<Void> prepared;
        try { prepared = Objects.requireNonNull(persistence.prepare(proposal), "Null prepare stage"); }
        catch (Throwable failure) {
            transactionFailed(proposal, failure);
            return false;
        }
        prepared.whenComplete((ignored, prepareError) -> Bukkit.getScheduler().runTask(menuAPI.getPlugin(), () -> {
            if (prepareError != null) {
                transactionFailed(proposal, prepareError);
                return;
            }
            if (!viewer.isOnline() || !currentView(viewer, sourceInventory) || !validateProposal(proposal)
                    || !matchesPlayerState(viewer, proposal)) {
                // The prepared SQL receipt is intentionally retained for recovery.
                transactionFailed(proposal, new IllegalStateException("Prepared transaction lost its viewer or source"));
                return;
            }
            // Escrow source items only after durable preparation, before updating vault SQL.
            Map<Integer, ItemStack> escrow;
            ItemStack escrowCursor;
            try {
                escrow = escrowPlayerSources(viewer, proposal);
                escrowCursor = SharedInventoryController.copy(viewer.getOpenInventory().getCursor());
            } catch (RuntimeException failure) {
                transactionFailed(proposal, failure);
                return;
            }
            CompletionStage<Void> committed;
            try { committed = Objects.requireNonNull(persistence.commit(proposal), "Null commit stage"); }
            catch (Throwable failure) {
                transactionFailed(proposal, failure);
                return;
            }
            committed.whenComplete((nothing, commitError) -> Bukkit.getScheduler().runTask(menuAPI.getPlugin(), () -> {
                if (commitError != null) {
                    transactionFailed(proposal, commitError);
                    return;
                }
                if (!viewer.isOnline() || !currentView(viewer, sourceInventory) || !validateProposal(proposal)
                        || !matchesEscrow(viewer, escrow, escrowCursor)) {
                    transactionFailed(proposal, new IllegalStateException("Committed transaction lost its escrow or viewer"));
                    return;
                }
                try {
                    apply(viewer, proposal);
                } catch (RuntimeException failure) {
                    transactionFailed(proposal, failure);
                    return;
                }
                finishTransaction();
                try {
                    persistence.delivered(proposal).whenComplete((ack, acknowledgeError) -> {
                        if (acknowledgeError != null)
                            menuAPI.getPlugin().getLogger().log(Level.SEVERE,
                                    "Shared inventory receipt remains unresolved: " + proposal.transactionId(), acknowledgeError);
                    });
                } catch (RuntimeException acknowledgeError) {
                    menuAPI.getPlugin().getLogger().log(Level.SEVERE,
                            "Unable to acknowledge shared inventory receipt: " + proposal.transactionId(), acknowledgeError);
                }
            }));
        }));
        return true;
    }

    /** Releases the mutation lock and retires unused shared Bukkit page inventories. */
    private void finishTransaction() {
        busy = false;
        if (viewers.isEmpty()) {
            pages.clear();
            closeTasks();
            safeCallback(this::onLastViewerClosed);
        }
    }

    /**
     * Escrows only the original source items, not newly received destination items.
     *
     * @param viewer the player viewing this shared inventory
     * @param proposal the proposed changes to validate or execute
     * @return true if source items were successfully taken into escrow
     */
    private Map<Integer, ItemStack> escrowPlayerSources(Player viewer, SharedInventoryTransaction proposal) {
        Map<Integer, ItemStack> escrowed = new HashMap<>();
        for (Map.Entry<Integer, ItemStack> entry : proposal.beforePlayerItems().entrySet()) {
            int slot = entry.getKey();
            ItemStack original = entry.getValue();
            ItemStack replacement = proposal.afterPlayerItems().get(slot);
            if (!SharedInventoryController.empty(original) && sourceShrinks(original, replacement)) {
                if (slot == 40) viewer.getInventory().setItemInOffHand(null);
                else viewer.getInventory().setItem(slot, null);
                escrowed.put(slot, null);
            } else {
                escrowed.put(slot, SharedInventoryController.copy(original));
            }
        }
        if (!SharedInventoryController.empty(proposal.beforeCursor())
                && sourceShrinks(proposal.beforeCursor(), proposal.afterCursor()))
            viewer.getOpenInventory().setCursor(null);
        return escrowed;
    }

    /**
     * Checks whether an existing stack is consumed or replaced as part of a transaction.
     *
     * @param before the item or state before the operation
     * @param after the item or state after the operation
     * @return true if source shrinks; false otherwise
     */
    private boolean sourceShrinks(ItemStack before, @Nullable ItemStack after) {
        return SharedInventoryController.empty(after) || !before.isSimilar(after)
                || after.getAmount() < before.getAmount();
    }

    /**
     * Rejects an out-of-band inventory/cursor modification during the durable commit.
     *
     * @param viewer the player viewing this shared inventory
     * @param expected the expected item or state
     * @param cursor the item currently held on the cursor
     * @return true if matches escrow; false otherwise
     */
    private boolean matchesEscrow(Player viewer, Map<Integer, ItemStack> expected, @Nullable ItemStack cursor) {
        if (!SharedInventoryController.same(viewer.getOpenInventory().getCursor(), cursor)) return false;
        for (Map.Entry<Integer, ItemStack> entry : expected.entrySet()) {
            ItemStack item = entry.getKey() == 40 ? viewer.getInventory().getItemInOffHand()
                    : viewer.getInventory().getItem(entry.getKey());
            if (!SharedInventoryController.same(item, entry.getValue())) return false;
        }
        return true;
    }

    /**
     * Applies optional per-item policies without exposing mutable canonical objects.
     *
     * @param viewer the player viewing this shared inventory
     * @param proposal the proposed changes to validate or execute
     * @return true if check item policies; false otherwise
     */
    private boolean checkItemPolicies(Player viewer, SharedInventoryTransaction proposal) {
        for (SharedItemChange change : proposal.changes()) {
            ItemStack before = change.before();
            ItemStack after = change.after();
            if (before != null && (after == null || !before.isSimilar(after)
                    || before.getAmount() > after.getAmount())
                    && !canExtract(viewer, before.clone(), change.storageSlot())) return false;
            if (after != null && (before == null || !before.isSimilar(after)
                    || after.getAmount() > before.getAmount())
                    && !canInsert(viewer, after.clone(), change.storageSlot())) return false;
        }
        return true;
    }

    /**
     * Enforces snapshot revision and all storage slot preconditions.
     *
     * @param proposal the proposed changes to validate or execute
     * @return true if validate proposal; false otherwise
     */
    private boolean validateProposal(SharedInventoryTransaction proposal) {
        if (proposal.expectedRevision() != revision || proposal.changes().isEmpty()
                || !conservesItems(proposal)
                || !SharedInventoryController.valid(proposal.beforeCursor())
                || !SharedInventoryController.valid(proposal.afterCursor())) return false;
        Map<Integer, ItemStack> beforePlayer = proposal.beforePlayerItems();
        Map<Integer, ItemStack> afterPlayer = proposal.afterPlayerItems();
        if (!beforePlayer.keySet().equals(afterPlayer.keySet())) return false;
        for (int slot : beforePlayer.keySet()) {
            if (slot < 0 || slot > 35 && slot != 40
                    || !SharedInventoryController.valid(beforePlayer.get(slot))
                    || !SharedInventoryController.valid(afterPlayer.get(slot))) return false;
        }
        Set<Integer> modifiedSlots = new HashSet<>();
        for (SharedItemChange change : proposal.changes()) {
            if (!modifiedSlots.add(change.storageSlot())
                    || !SharedInventoryController.valid(change.before())) return false;
            if (change.storageSlot() < 0 || change.storageSlot() / itemsPerPage() >= getSharedPages()
                    || !SharedInventoryController.same(readStorageItem(change.storageSlot()), change.before())
                    || !SharedInventoryController.valid(change.after())
                    || change.storageSlot() / itemsPerPage() != change.page()
                    || visualSlot(change.storageSlot()) != change.slot()) return false;
            if (!SharedInventoryController.empty(change.after()) && toStoredItem(change.after()) == null) return false;
        }
        return true;
    }

    /**
     * Rejects any proposal that creates, destroys or changes an item type across the storage,
     * player inventory and cursor boundary. Every source and destination must balance exactly.
     *
     * @param proposal the proposed changes to validate or execute
     * @return true if conserves items; false otherwise
     */
    private boolean conservesItems(SharedInventoryTransaction proposal) {
        Map<ItemStack, Long> balance = new HashMap<>();
        for (SharedItemChange change : proposal.changes()) {
            account(balance, change.before(), -1);
            account(balance, change.after(), 1);
        }
        proposal.beforePlayerItems().values().forEach(item -> account(balance, item, -1));
        proposal.afterPlayerItems().values().forEach(item -> account(balance, item, 1));
        account(balance, proposal.beforeCursor(), -1);
        account(balance, proposal.afterCursor(), 1);
        return balance.values().stream().allMatch(value -> value == 0L);
    }

    /**
     * Normalizes amount to one for value equality, accumulating the exact item count.
     *
     * @param totals the mutable item counts collected during validation
     * @param item the item stack involved in the operation
     * @param sign the multiplier used to add or subtract item counts
     */
    private void account(Map<ItemStack, Long> totals, @Nullable ItemStack item, int sign) {
        if (SharedInventoryController.empty(item)) return;
        ItemStack key = item.clone();
        key.setAmount(1);
        totals.merge(key, (long) sign * item.getAmount(), Long::sum);
    }

    /**
     * Confirms that untracked inventory actions could not change the inputs while SQL was pending.
     *
     * @param viewer the player viewing this shared inventory
     * @param proposal the proposed changes to validate or execute
     * @return true if matches player state; false otherwise
     */
    private boolean matchesPlayerState(Player viewer, SharedInventoryTransaction proposal) {
        if (!SharedInventoryController.same(viewer.getOpenInventory().getCursor(), proposal.beforeCursor())) return false;
        for (Map.Entry<Integer, ItemStack> entry : proposal.beforePlayerItems().entrySet()) {
            ItemStack live = entry.getKey() == 40 ? viewer.getInventory().getItemInOffHand()
                    : viewer.getInventory().getItem(entry.getKey());
            if (!SharedInventoryController.same(live, entry.getValue())) return false;
        }
        return true;
    }

    /**
     * Applies a successful transaction once to canonical storage and shared Bukkit pages.
     *
     * @param viewer the player viewing this shared inventory
     * @param proposal the proposed changes to validate or execute
     */
    private void apply(Player viewer, SharedInventoryTransaction proposal) {
        List<Runnable> legacyNotifications = new ArrayList<>(proposal.changes().size());
        for (SharedItemChange change : proposal.changes()) {
            int absolute = change.storageSlot();
            T previous = stored.getFillItem(absolute);
            T updated = SharedInventoryController.empty(change.after()) ? null : toStoredItem(change.after());
            stored.setAt(absolute, updated);
            SharedMenuInventoryHolder holder = pages.get(change.page());
            if (holder != null) holder.getInventory().setItem(change.slot(), change.after());
            legacyNotifications.add(() -> onSharedItemsChanged(viewer, absolute, previous, updated));
        }
        proposal.afterPlayerItems().forEach((slot, item) -> {
            if (slot == 40) viewer.getInventory().setItemInOffHand(item);
            else viewer.getInventory().setItem(slot, item);
        });
        viewer.getOpenInventory().setCursor(proposal.afterCursor());
        revision++;
        // Notifications must observe the entire committed transaction, not a half-written page.
        legacyNotifications.forEach(this::safeCallback);
        // An AUTO storage may shrink when its last occupied slot is cleared. A viewer
        // must never stay attached to a page that no longer exists.
        int pageCount = getSharedPages();
        for (Map.Entry<UUID, Integer> entry : new ArrayList<>(viewers.entrySet())) {
            if (entry.getValue() < pageCount) continue;
            Player online = Bukkit.getPlayer(entry.getKey());
            if (online != null) Bukkit.getScheduler().runTask(menuAPI.getPlugin(),
                    () -> setPage(online, pageCount - 1));
        }
        safeCallback(() -> onItemsChanged(proposal));
        notifySlotCallbacks(proposal);
        safeCallback(() -> Bukkit.getPluginManager().callEvent(new SharedInventoryPostChangeEvent(proposal)));
    }

    /**
     * Classifies exact deposits, withdrawals and swaps without confusing equal-sized different types.
     *
     * @param transaction the immutable transaction describing the proposed or committed changes
     */
    private void notifySlotCallbacks(SharedInventoryTransaction transaction) {
        boolean storageOnly = transaction.beforePlayerItems().isEmpty()
                && SharedInventoryController.same(transaction.beforeCursor(), transaction.afterCursor());
        for (SharedItemChange change : transaction.changes()) {
            ItemStack before = change.before();
            ItemStack after = change.after();
            if (storageOnly) {
                safeCallback(() -> onItemMoved(change));
                continue;
            }
            if (before != null && after != null && before.isSimilar(after)) {
                if (after.getAmount() > before.getAmount()) safeCallback(() -> onItemDeposited(change));
                else if (after.getAmount() < before.getAmount()) safeCallback(() -> onItemWithdrawn(change));
                continue;
            }
            if (before != null) {
                SharedItemChange withdrawn = detachedChange(transaction, change, before, null);
                safeCallback(() -> onItemWithdrawn(withdrawn));
            }
            if (after != null) {
                SharedItemChange inserted = detachedChange(transaction, change, null, after);
                safeCallback(() -> onItemDeposited(inserted));
            }
        }
    }

    /**
     * Creates a delta-only event snapshot while retaining historical player and transaction identity.
     *
     * @param transaction the immutable transaction describing the proposed or committed changes
     * @param source the source of the slot change
     * @param before the item or state before the operation
     * @param after the item or state after the operation
     * @return a defensive copy of the supplied value, or null when no value existsed change
     */
    private SharedItemChange detachedChange(SharedInventoryTransaction transaction, SharedItemChange source,
                                            @Nullable ItemStack before, @Nullable ItemStack after) {
        return new SharedItemChange(source.page(), source.slot(), source.storageSlot(), before, after,
                transaction.playerId(), transaction.playerName(), transaction.transactionId(), transaction.storageId());
    }

    /**
     * Isolates user code from canonical inventory commits.
     *
     * @param callback the user-provided callback to invoke
     */
    private void safeCallback(Runnable callback) {
        try { callback.run(); }
        catch (RuntimeException failure) {
            menuAPI.getPlugin().getLogger().log(Level.SEVERE, "Shared inventory callback failed", failure);
        }
    }

    /**
     * Permanently locks uncertain storage and closes all viewers.
     *
     * @param transaction the immutable transaction describing the proposed or committed changes
     * @param failure the failure that prevented the transaction from completing
     */
    private void transactionFailed(SharedInventoryTransaction transaction, Throwable failure) {
        busy = false;
        quarantined = true;
        menuAPI.getPlugin().getLogger().log(Level.SEVERE,
                "Shared inventory transaction requires review: " + transaction.transactionId(), failure);
        safeCallback(() -> onTransactionFailed(transaction, failure));
        safeCallback(() -> Bukkit.getPluginManager().callEvent(new SharedInventoryTransactionFailedEvent(transaction, failure)));
        for (UUID id : new ArrayList<>(viewers.keySet())) {
            Player viewer = Bukkit.getPlayer(id);
            if (viewer != null) Bukkit.getScheduler().runTask(menuAPI.getPlugin(), () -> viewer.closeInventory());
        }
    }

    /** Rejects configuration changes while any shared Bukkit view exists. */
    private void ensureConfigurable() {
        if (!viewers.isEmpty() || busy) throw new IllegalStateException("Configure shared inventory before opening it");
    }

    /** Enforces Paper's single-region synchronous Bukkit inventory access. */
    private void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Shared inventory must be accessed on the server thread");
    }

    /** Validates configured capacity before displaying items. */
    private void validateCapacity() {
        if (stored.size() > getSharedPages() * itemsPerPage())
            throw new IllegalArgumentException("Storage exceeds configured maximum pages");
    }

    /** Precomputes visual-slot mapping once per configuration rather than once per click. */
    private void configureSlots() {
        if (!fillSlots.isEmpty()) return;
        int size = getInventorySize();
        if (size <= 0 || (size % 9 != 0 && size != 5))
            throw new IllegalArgumentException("Shared inventory must have 9-54 chest slots or 5 hopper slots");
        if (getFillSpace() == null || getFillSpace().isEmpty()) {
            int last = size > 9 ? size - 10 : size - 1;
            setFillSpace(IntStream.rangeClosed(0, last).boxed().collect(Collectors.toList()));
        }
        fillSlots = Collections.unmodifiableList(getFillSpace().stream().distinct().sorted()
                .collect(Collectors.toList()));
        if (fillSlots.isEmpty() || fillSlots.get(0) < 0 || fillSlots.get(fillSlots.size() - 1) >= size)
            throw new IllegalArgumentException("Invalid shared storage slots");
        if (getMenuSizeInvalid(size)) throw new IllegalArgumentException("Unsupported shared inventory size: " + size);
    }

    /**
     * Ensures that an inventory has a size supported by Bukkit inventory constructors.
     *
     * @param size the number of inventory slots
     * @return true if get menu size invalid; false otherwise
     */
    private boolean getMenuSizeInvalid(int size) { return size != 5 && (size < 9 || size > 54 || size % 9 != 0); }

    /**
     * Creates a page inventory lazily, shared by every viewer of that page.
     *
     * @param viewer the player viewing this shared inventory
     * @param page the zero-based page index
     * @return the lazily created inventory holder for the requested page
     */
    private SharedMenuInventoryHolder pageHolder(Player viewer, int page) {
        SharedMenuInventoryHolder cached = pages.get(page);
        if (cached != null) return cached;
        renderingViewer.set(viewer);
        String title;
        try { title = String.valueOf(getTitle()); }
        finally { renderingViewer.remove(); }
        SharedMenuInventoryHolder holder = new SharedMenuInventoryHolder(this, storageId, page);
        // Use the same Bukkit inventory creation API as the existing InventoryRenderer.
        Inventory inventory = getInventorySize() == 5
                ? Bukkit.createInventory(holder, InventoryType.HOPPER, title)
                : Bukkit.createInventory(holder, getInventorySize(), title);
        holder.attach(inventory);
        renderItems(inventory, page);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (isFillSlot(slot)) continue;
            MenuButton button = getButtonAt(slot);
            if (button != null) inventory.setItem(slot, SharedInventoryController.copy(button.getItem()));
        }
        pages.put(page, holder);
        return holder;
    }

    /**
     * Opens a page while suppressing the close callback of the replaced view.
     *
     * @param viewer the player viewing this shared inventory
     * @param page the zero-based page index
     */
    private void openPage(Player viewer, int page) {
        UUID id = viewer.getUniqueId();
        switching.add(id);
        try {
            viewers.put(id, page);
            this.player = viewer;
            this.pageNumber = page;
            Inventory inventory = pageHolder(viewer, page).getInventory();
            setMenu(inventory);
            menuAPI.getPlayerMeta().setPlayerMenuMetadata(viewer, MenuMetadataKey.MENU_OPEN, this);
            viewer.openInventory(inventory);
            onMenuOpenPlaySound();
        } finally { switching.remove(id); }
    }

    /**
     * Rebuilds storage slots without sorting, compacting or shifting any item.
     *
     * @param inventory the Bukkit inventory being used
     * @param page the zero-based page index
     */
    private void renderItems(Inventory inventory, int page) {
        for (int visual : fillSlots) {
            int absolute = storageIndex(page, visual);
            inventory.setItem(visual, readStorageItem(absolute));
        }
    }

    /**
     * Returns true only if this viewer has the exact shared inventory open.
     *
     * @param viewer the player viewing this shared inventory
     * @param inventory the Bukkit inventory being used
     * @return true if current view; false otherwise
     */
    private boolean currentView(Player viewer, Inventory inventory) {
        if (!(inventory.getHolder() instanceof SharedMenuInventoryHolder)) return false;
        SharedMenuInventoryHolder holder = (SharedMenuInventoryHolder) inventory.getHolder();
        Integer page = viewers.get(viewer.getUniqueId());
        return page != null && page == holder.page() && pages.get(page) == holder && holder.owner() == this
                && holder.getInventory() == inventory;
    }

    /**
     * Read-only visual fill slots.
     *
     * @return the configured visual storage slots
     */
    List<Integer> fillSlots() { return fillSlots; }
    /**
     * Page count mode for shift-click.
     *
     * @return the configured quick-transfer mode
     */
    SharedTransferMode transferMode() { return transferMode; }
    /**
     * Stable storage ID exposed to the proposal simulator.
     *
     * @return the UUID uniquely identifying the shared storage
     */
    UUID storageId() { return storageId; }
    /**
     * Snapshot revision exposed to the proposal simulator.
     *
     * @return the current committed storage revision
     */
    long revision() { return revision; }
    /**
     * Returns the number of real fill slots per page.
     *
     * @return the number of available storage slots on each page
     */
    int itemsPerPage() { return Math.max(1, fillSlots.size()); }
    /**
     * Converts a physical slot on one page to a stable absolute storage index.
     *
     * @param page the zero-based page index
     * @param visual the slot index shown in the inventory
     * @return the absolute stable storage slot index
     */
    int storageIndex(int page, int visual) {
        int offset = java.util.Collections.binarySearch(fillSlots, visual);
        return offset < 0 ? -1 : page * itemsPerPage() + offset;
    }
    /**
     * Converts a global storage index to its physical visual slot.
     *
     * @param absolute the zero-based stable storage slot index
     * @return the visual slot index on the inventory page
     */
    int visualSlot(int absolute) { return fillSlots.get(absolute % itemsPerPage()); }
    /**
     * Retrieves a defensive storage item snapshot.
     *
     * @param absolute the zero-based stable storage slot index
     * @return the item currently stored at the requested slot
     */
    @Nullable ItemStack readStorageItem(int absolute) {
        return SharedInventoryController.copy(toDisplayItem(stored.getFillItem(absolute), absolute));
    }
    /**
     * Identifies a physical storage slot without scanning all buttons.
     *
     * @param visual the slot index shown in the inventory
     * @return true if the visual slot belongs to the shared storage
     */
    private boolean isFillSlot(int visual) { return java.util.Collections.binarySearch(fillSlots, visual) >= 0; }
    /**
     * Normalized amount used to classify callback notifications.
     *
     * @param item the item stack involved in the operation
     * @return the number of items in the stack
     */
    private int amount(@Nullable ItemStack item) { return SharedInventoryController.empty(item) ? 0 : item.getAmount(); }
}
