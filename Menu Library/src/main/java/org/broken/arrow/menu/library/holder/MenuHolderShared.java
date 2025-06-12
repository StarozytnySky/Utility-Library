package org.broken.arrow.menu.library.holder;

import org.broken.arrow.menu.library.MenuMetadataKey;
import org.broken.arrow.menu.library.RegisterMenuAPI;
import org.broken.arrow.menu.library.builders.ButtonData;
import org.broken.arrow.menu.library.builders.MenuDataUtility;
import org.broken.arrow.menu.library.button.MenuButton;
import org.broken.arrow.menu.library.button.MenuButtonPage;
import org.broken.arrow.menu.library.cache.PlayerMenuCache;
import org.broken.arrow.menu.library.holder.utility.AnimateTitleTask;
import org.broken.arrow.menu.library.runnable.ButtonAnimation;
import org.broken.arrow.menu.library.utility.FillItems;
import org.broken.arrow.menu.library.utility.Function;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

public abstract class MenuHolderShared<T> extends HolderUtility<T> {
	private final List<Inventory> sharedPages = new ArrayList<>();
	private final PlayerMenuCache playerMenuCache = new PlayerMenuCache();
	private FillItems<T> listOfFillItems;
	private final Map<Integer, Integer> fillSlotsMapping = new HashMap<>();
	private boolean savePlayerPage = true;
	private int totalPages;
	private final Map<Player, AnimateTitleTask<T>> titleTasks = new HashMap<>();
	private final Logger logger = Logger.getLogger(MenuHolderShared.class.getName());
	private final Set<Player> viewers = new HashSet<>();
	private final Map<Integer, ButtonAnimation<T>> pageAnimationTasks = new HashMap<>(); // NEW: Page-specific tasks


	protected MenuHolderShared(int totalPages, int inventorySize) {
		this(totalPages, inventorySize, null);
	}

	protected MenuHolderShared(int totalPages, int inventorySize, @Nullable List<T> fillItems) {
		this(totalPages, inventorySize, null, fillItems);
	}

	protected MenuHolderShared(int totalPages, int inventorySize, @Nullable List<Integer> fillSlots, @Nullable List<T> fillItems) {
		this(totalPages, inventorySize, fillSlots, fillItems, true);
	}

	protected MenuHolderShared(int totalPages, int inventorySize, @Nullable List<Integer> fillSlots, @Nullable List<T> fillItems, boolean shallCacheItems) {
		this(RegisterMenuAPI.getMenuAPI(), totalPages, inventorySize, fillSlots, fillItems, shallCacheItems);
	}

	protected MenuHolderShared(@Nonnull RegisterMenuAPI menuAPI, int totalPages, int inventorySize, @Nullable List<Integer> fillSlots, @Nullable List<T> fillItems, boolean shallCacheItems) {
		super(menuAPI, fillSlots, shallCacheItems);
		this.totalPages = totalPages;
		this.inventorySize = inventorySize;

		this.amountOfPages();
		updateSharedPages();
		if (fillItems != null) {
			setFillItems(fillItems);
		}
	}

	public void setSavePlayerPage(boolean savePlayerPage) {
		this.savePlayerPage = savePlayerPage;
	}

	/**
	 * Sets the list of fill items for this menu holder. The provided list will overwrite the existing
	 * set of items and will be used to populate the relevant menu slots. Items in the list will be
	 * distributed across the menu's pages based on their order and the available slots.
	 *
	 * @param fillItems the list of fill items to set. Each item represents an individual element
	 *                  that can be placed in the menu. The list can contain null values and its
	 *                  order is preserved during the distribution to menu slots.
	 */
	public void setFillItems(List<T> fillItems) {
		if(fillItems == null || fillItems.isEmpty()) {
			return;
		}
		this.listOfFillItems = new org.broken.arrow.menu.library.utility.FillItems<>();
		this.listOfFillItems.setFillItems(fillItems);

		distributeFillItems();
	}


	/**
	 * Distributes fill items across the available pages and slots in the menu.
	 *
	 * This method populates the designated fill slots with items from a predefined fill items list.
	 * The items are distributed sequentially across all pages, filling each page's fill slots before
	 * proceeding to the next page. If there are more slots than items, the remaining slots are left
	 * empty. The method also ensures that additional buttons are set to the inventory after filling
	 * the fill slots.
	 *
	 * Each inventory on a page is updated with the appropriate fill items based on its assigned
	 * slots and the distribution logic.
	 *
	 * Internal workflow:
	 * - Fetches the fill slots available using the `getFillSpace()` method.
	 * - Iterates through all pages and their inventories.
	 * - Fills the slots with items from the fill items list, leaving empty slots if no item
	 *   is available for a specific index.
	 * - Calls the `setButtons` method to attach additional buttons to the inventory of each page.
	 */
	private void distributeFillItems() {
		List<Integer> fillSlots = getFillSpace();
		int itemsPerPage = fillSlots.size();
		for (int page = 0; page < totalPages; page++) {
			Inventory inv = sharedPages.get(page);
			for (int i = 0; i < itemsPerPage; i++) {
				int itemIndex = page * itemsPerPage + i;
				inv.setItem(fillSlots.get(i), itemIndex < getListOfFillItem().getFillItems().size() ? (ItemStack) getListOfFillItem().getFillItem(itemIndex) : null);
				setButtons(inv);

			}
		}
	}

	/**
	 * Helper method to create pages
	 */
	private void updateSharedPages() {
		sharedPages.clear();

		for (int i = 0; i < totalPages; i++) {
			Inventory inv = Bukkit.createInventory(null, inventorySize, "Shared Menu Page " + (i + 1));
			sharedPages.add(inv);
		}
	}

	/**
	 * Sets the menu state by updating the items in the shared pages based on the provided state mapping.
	 *
	 * The method updates each page in the shared pages, populating the specified slots with the corresponding
	 * `ItemStack`s from the provided state map. It ensures that the inventory state is updated and logs the
	 * changes for debugging purposes. Additionally, menu buttons are configured for each updated page.
	 * If there are fill items set, it distributes them across all pages after setting the menu state.
	 *
	 * @param state a map representing the menu state, where the key is the page index (starting from 0),
	 *              and the value is another map that associates slot indexes in the inventory with their
	 *              corresponding `ItemStack` items. If a page or slot is not present in the mapping,
	 *              it will not be modified (or left empty in case of new pages).
	 */
	public void setMenuState(Map<Integer, Map<Integer, ItemStack>> state) {
		updateSharedPages();
		for (int page = 0; page < sharedPages.size(); page++) {
			Inventory inv = sharedPages.get(page);
			Map<Integer, ItemStack> slots = state.getOrDefault(page, new HashMap<>());
			for (int slot : getFillSpace()) {
				ItemStack item = slots.get(slot);
				inv.setItem(slot, item);
				updateInventoryState(page, slot, item);
				logger.info("DEBUG: Set state for page " + page + ", slot " + slot + ", item: " + (item != null ? item.getType() : "null"));
			}
			setButtons(inv);
			logger.info("DEBUG: Loaded menu state for page " + page);
		}
		if (listOfFillItems != null) {
			setMenuItemsToAllPages();
		}
	}

	private void updateInventoryState(int page, int slot, @Nullable ItemStack item) {
		if (page < sharedPages.size()) {
			Inventory inv = sharedPages.get(page);
			inv.setItem(slot, item);
			MenuDataUtility<T> menuData = getMenuData(page);
			if (menuData != null) {
				ButtonData<T> buttonData = menuData.getButton(getSlot(slot));
				if (buttonData != null) {
					menuData.putButton(getSlot(slot), new ButtonData<>(item, buttonData.getMenuButton(), buttonData.getObject()));
					putAddedButtonsCache(page, menuData);
					logger.info("DEBUG: Updated inventory state for page " + page + ", slot " + slot + ", item: " + (item != null ? item.getType() : "null"));
				}
			}
		}
	}

	protected final void amountOfPages() {
		this.getMenuRenderer().setAmountOfPages(() -> {
			if (getListOfFillItem() == null) return (double) getManuallySetPages();

			final List<T> fillItems = this.getListOfFillItem().getFillItems();
			final List<Integer> fillSlots = this.getFillSpace();
			if (this.itemsPerPage > 0) {
				if (!fillSlots.isEmpty()) {
					return (double) fillSlots.size() / this.itemsPerPage;
				} else if (fillItems != null && !fillItems.isEmpty()) return (double) fillItems.size() / this.itemsPerPage;
			}
			if (fillItems != null && !fillItems.isEmpty()) {
				return (double) fillItems.size() / (fillSlots.isEmpty() ? this.inventorySize - 9 : fillSlots.size());
			}
			return (double) getManuallySetPages();
		});
	}

	private void setMenuItemsToAllPages() {
		getMenuRenderer().setRequiredPages(Math.max((int) (double) getTotalPages(), 1));
		if (this.getManuallySetPages() > 0)
			getMenuRenderer().setRequiredPages(getManuallySetPages());
		for (int i = 0; i < this.getRequiredPages(); i++) {
			getMenuRenderer().setMenuItemsToPage(i, true);
			if (i == 0)
				getMenuRenderer().setNumberOfFillItems(this.slotIndex);
		}
		this.slotIndex = 0;
		logger.info("DEBUG: Rendered fill items for all pages");
	}

//	public abstract FillMenuButton<T> createFillMenuButton();

//	@Nullable
//	@Override
//	public MenuButton getFillButtonAt(int slot) {
//		if (slot == -1) return null;
//
//		FillMenuButton<T> fillMenuButton = createFillMenuButton();
//		if (fillMenuButton != null) return new MenuButtonPage<T>() {
//			@Override
//			public void onClickInsideMenu(@Nonnull Player player, @Nonnull Inventory menu, @Nonnull ClickType click, @Nonnull ItemStack clickedItem, @Nullable T fillItem) {
//				ButtonUpdateAction buttonUpdateAction = fillMenuButton.getClick().apply(player, menu, click, clickedItem, fillItem);
//
//				System.out.println("Click button item: " + clickedItem);
//				switch (buttonUpdateAction) {
//					case ALL:
//						updateButtons(player);
//						break;
//					case THIS:
//						updateButton(player,this);
//						break;
//					case NONE:
//						break;
//				}
//			}
//
//			@Override
//			public long setUpdateTime() {
//				return fillMenuButton.getUpdateTime();
//			}
//
//			@Override
//			public boolean shouldUpdateButtons() {
//				return fillMenuButton.isUpdateButtonsTimer();
//			}
//
//			@Override
//			public ItemStack getItem(int slot, @Nullable T fillItem) {
//				OnRetrieveItem<ItemStack, Integer, T> menuItem = fillMenuButton.getMenuFillItem();
//				ItemStack item = (ItemStack) fillItem;
//				ItemMeta meta = item.getItemMeta();
//				meta.setLore(Collections.singletonList("Click to take it"));
//				item.setItemMeta(meta);
//
//				return menuItem.apply(slot, (T) item);
//			}
//		};
//		return null;
//	}

	/**
	 * Sets the title for the menu of a specific player using a provided function.
	 * The function dynamically generates the title as a string. If the function
	 * produces a non-null and non-empty string, the menu title is updated to the
	 * generated value.
	 *
	 * @param player   The player for whom the title is being set. Cannot be null.
	 * @param function A function that generates the new title as a string. Cannot be null.
	 */
	public void setTitle(Player player, org.broken.arrow.menu.library.utility.Function<String> function) {
		PlayerMenuCache.PlayerMenuData data = playerMenuCache.getPlayerData(player);
		data.setTitleFunction(function);
		String title = function.apply();
		if (title != null && !title.isEmpty()) {
			updateTitle(player, title);
		}
	}

	/**
	 * Sets an animated title for the specified player's menu using a provided function.
	 * The title will update dynamically based on the function, and the animation will run
	 * for the specified duration. If there is an existing animation task for the player,
	 * it will be stopped before starting the new one.
	 *
	 * @param player   The player for whom the animated title is being set. Cannot be null.
	 * @param time     The duration of the animation in ticks. Must be a positive integer.
	 * @param function A function that dynamically generates the title as a string. Cannot be null.
	 */
	public void setAnimateTitle(Player player, int time, org.broken.arrow.menu.library.utility.Function<String> function) {
		PlayerMenuCache.PlayerMenuData data = playerMenuCache.getPlayerData(player);
		data.setAnimateTitleFunction(function);
		data.setAnimateTitleTime(time);
		AnimateTitleTask<T> existingTask = titleTasks.get(player);
		if (existingTask != null && existingTask.isRunning()) {
			existingTask.stopTask();
		}
		AnimateTitleTask<T> newTask = new AnimateTitleTask<>(this, player);
		newTask.runTask(20L + time);
		titleTasks.put(player, newTask);
		data.setAnimateTitleTaskId(newTask.getTaskId());
	}

	private void setButtons(Inventory inventory) {
		MenuDataUtility<T> menuData = getMenuData(sharedPages.indexOf(inventory));
		if (menuData == null) {
			menuData = new MenuDataUtility<>();
			putAddedButtonsCache(sharedPages.indexOf(inventory), menuData);
		}
		for (int slot = 0; slot < inventorySize; slot++) {
			MenuButton button = getButtonAt(slot);
			if (button != null) {
				ItemStack item = button.getItem();
				if (item != null) {
					inventory.setItem(slot, item);
					menuData.putButton(slot, new ButtonData<>(item, button, null));
					if (button.shouldUpdateButtons()) {
						getButtonsToUpdate().add(button);
					}
					logger.info("DEBUG: Set button at slot " + slot + ": " + item.getType()); // NEW: Log button
				} else {
					logger.warning("DEBUG: Button at slot " + slot + " has null item");
				}
			}
		}
		putAddedButtonsCache(sharedPages.indexOf(inventory), menuData);
		logger.info("DEBUG: Buttons set for inventory, buttons to update: " + getButtonsToUpdate().size()); // NEW: Log update count
	}

	/**
	 * Opens the menu for the specified player at the provided location.
	 * This method handles inventory setup and caching for the player,
	 * maintaining state for the menu system and managing visual elements.
	 *
	 * @param player   The player for whom the menu is being opened. Cannot be null.
	 * @param location The location associated with the menu opening. Can be null.
	 */
	@Override
	public void menuOpen(@Nonnull Player player, @Nullable Location location) {
		player.closeInventory();

		// When `savePlayerPage` is true, then we load page from cache instead start at first page
		// Set a page for the player in the cache
		PlayerMenuCache.PlayerMenuData data = playerMenuCache.getPlayerData(player);
		int page = savePlayerPage ? data.getCurrentPage() : 0;
		data.setCurrentPage(page);
		Inventory inventory = sharedPages.get(page);

		// Set inventory for the player in the cache (IMPORTANT)
		data.setInventory(inventory);

		// Redraw only buttons registered in getButtonAt to prevent sort fillItems
		// We want to keep fillItems in slots they are already there because if someone already has opened a menu then
		// should be unchanged. (just keep maintain functionality like a chest)
		redrawInventory(player);

		Inventory loadedInventory = loadInventory(player, location, true);

		if (loadedInventory == null) {
			return;
		}

		//data.setInventory(loadedInventory);

		player.openInventory(inventory);
		this.viewers.add(player);

		onMenuOpenPlaySound(player);
		updatePageAnimation(page); // NEW: Start page animation

		if (!getButtonsToUpdate().isEmpty())
			updateButtonsInList(player);

		menuAPI.getPlayerMeta().setPlayerMenuMetadata(player, org.broken.arrow.menu.library.MenuMetadataKey.MENU_OPEN, this);
	}

	// NEW: Start/stop page animation based on viewers
	private void updatePageAnimation(int page) {
		boolean hasViewers = viewers.stream().anyMatch(viewer -> {
			PlayerMenuCache.PlayerMenuData data = playerMenuCache.getPlayerData(viewer);
			return data.getCurrentPage() == page;
		});
		ButtonAnimation<T> task = pageAnimationTasks.get(page);
		if (hasViewers && (task == null || !task.isRunning())) {
			task = new ButtonAnimation<>(null, this);
			task.runTask(getUpdateTime());
			pageAnimationTasks.put(page, task);
			logger.info("DEBUG: Started ButtonAnimation for page " + page);
		} else if (!hasViewers && task != null && task.isRunning()) {
			task.stopTask();
			pageAnimationTasks.remove(page);
			logger.info("DEBUG: Stopped ButtonAnimation for page " + page);
		}
	}

	// ===========================================================================================
	// ======================================= Page Change =======================================
	// ===========================================================================================

	/**
	 * Navigates to the previous page of the menu for the specified player.
	 *
	 * @param player The player whose menu page is to be navigated. Cannot be null.
	 * @param circle Determines whether the pages should be navigated in a circular manner.
	 *               If true, navigating backwards on the first page will switch to the last page.
	 *               Otherwise, it will remain on the first page.
	 */
	public void previousPage(Player player, boolean circle) {
		changePage(player,false, circle);
	}

	/**
	 * Navigates to the next page of the menu for the specified player.
	 *
	 * @param player The player whose menu page is to be navigated. Cannot be null.
	 * @param circle Determines whether the pages should be navigated in a circular manner.
	 *               If true, navigating forward on the last page will switch to the first page.
	 *               Otherwise, it will remain on the last page.
	 */
	public void nextPage(Player player, boolean circle) {
		changePage(player, true, circle);
	}

	/**
	 * Changes the current page of the menu for a specific player.
	 *
	 * @param player   The player whose menu page is to be changed.
	 * @param nextPage Whether to switch to the next page (if true) or the previous page (if false).
	 * @param circle   Determines whether the pages should be navigated in a circular manner.
	 *                 If true, navigating backwards on the first page will switch to the last page
	 *                 or navigating forward on the last page will switch to the first page.
	 */
	private void changePage(Player player, boolean nextPage, boolean circle) {
		PlayerMenuCache.PlayerMenuData data = playerMenuCache.getPlayerData(player);
		int currentPage = data.getCurrentPage();
		int newPage;

		if (circle && totalPages > 1) {
			newPage = nextPage ? (currentPage + 1) % totalPages : (currentPage - 1 + totalPages) % totalPages;
		} else {
			newPage = nextPage ? Math.min(currentPage + 1, totalPages - 1) : Math.max(currentPage - 1, 0);
		}

		// Set metadata `PAGE_CHANGE` to prevent removing cache for player when he changes page
		menuAPI.getPlayerMeta().setPlayerMenuMetadata(player, MenuMetadataKey.PAGE_CHANGE, this);
		data.setCurrentPage(newPage);
		player.closeInventory();

		Bukkit.getScheduler().runTaskLater(getPlugin(), () -> {
			Inventory inv = sharedPages.get(newPage);

			// Update inventory in the player cache
			data.setInventory(inv);
			player.openInventory(inv);

			// Update the title for a menu after page changed (animated or normal title)
			if (data.getAnimateTitleFunction() != null) {
				setAnimateTitle(player, data.getAnimateTitleTime(), data.getAnimateTitleFunction());
			} else {
				Function<String> titleFunction = data.getTitleFunction();
				updateTitle(player, titleFunction != null ? titleFunction.apply() : getTitle());
			}

			updatePageAnimation(newPage); // NEW: Start new page animation
			updatePageAnimation(currentPage); // NEW: Update old page animation

			// After changed page set metadata again on `MENU_OPEN`
			menuAPI.getPlayerMeta().removePlayerMenuMetadata(player, MenuMetadataKey.PAGE_CHANGE);
			menuAPI.getPlayerMeta().setPlayerMenuMetadata(player, MenuMetadataKey.MENU_OPEN, this);

			logger.info("DEBUG: Changed to page " + newPage + " for player " + player.getName());
		}, 1);
	}

	@Override
	public void onClick(@Nonnull MenuButton menuButton, @Nonnull Player player, int clickedPos, @Nonnull ClickType clickType, @Nonnull ItemStack clickedItem) {
		int slot = fillSlotsMapping.getOrDefault(clickedPos, clickedPos);
		Inventory inventory = playerMenuCache.getPlayerData(player).getInventory();
		if (inventory != null) {
			if (menuButton instanceof MenuButtonPage) {
				T object = getFillItem(slot);
				((MenuButtonPage<T>) menuButton).onClickInsideMenu(player, inventory, clickType, clickedItem, object);
			} else {
				menuButton.onClickInsideMenu(player, inventory, clickType, clickedItem);
			}
		}
	}

	@Override
	public void menuClose(Player player, InventoryCloseEvent event) {
		// Tricky way to ensure we don't clear cache for player after page change
		// and ensure if already opened inventory is the same as in cache (to prevent a unique situation when cache will not be cleared correctly)
		if (!menuAPI.getPlayerMeta().hasPlayerMetadata(player, MenuMetadataKey.PAGE_CHANGE)) {
			Bukkit.getScheduler().runTaskLater(getPlugin(), () -> {
				PlayerMenuCache.PlayerMenuData data = playerMenuCache.getPlayerData(player);

				if(!data.getInventory().equals(player.getOpenInventory().getTopInventory())) {
					System.out.println("DEBUG: Closed menu for player " + player.getName() + ", metadata removed");
					playerMenuCache.removePlayerData(player);
					AnimateTitleTask<T> playerTask = titleTasks.get(player);
					if (playerTask != null && playerTask.isRunning()) {
						playerTask.stopTask();
						titleTasks.remove(player);
					}

					updatePageAnimation(data.getCurrentPage()); // NEW: Update page animation
					viewers.remove(player);

					// Stop animated button when none see the menu
					if(viewers.isEmpty()){
						closeTasks();
					}

					menuAPI.getPlayerMeta().removePlayerMenuMetadata(player, org.broken.arrow.menu.library.MenuMetadataKey.MENU_OPEN);
				}
			},2);
		}
	}

	@Override
	public void setButton(int pageNumber, MenuDataUtility<T> menuDataUtility, int slot, int fillSlotIndex, boolean isLastFillSlot) {
		int fillSlot = isLastFillSlot ? -1 : fillSlotIndex;
		final MenuButton menuButton = getMenuButtonAtSlot(slot, fillSlot);
		final ItemStack result = getItemAtSlot(menuButton, slot, fillSlot);
		if (pageNumber == getPageNumber() && fillSlot >= 0) {
			this.fillSlotsMapping.put(slot, fillSlot);
		}
		if (menuButton != null) {
			T fillItem = getFillItem(fillSlot);
			if (menuButton.shouldUpdateButtons()) {
				getButtonsToUpdate().add(menuButton);
			}
			boolean shallAddMenuButton = !isLastFillSlot && isFillSlot(slot) && this.getListOfFillItems() != null && !this.getListOfFillItems().isEmpty();
			final ButtonData<T> buttonData = new ButtonData<>(result, shallAddMenuButton ? null : menuButton, fillItem);
			menuDataUtility.putButton(this.getSlot(slot), buttonData, shallAddMenuButton ? menuButton : null);
			logger.info("DEBUG: Setting ButtonData for slot " + slot + ", item: " + (result != null ? result.getType() : "null") + ", menuButton: " + (menuButton != null ? menuButton.getClass().getSimpleName() : "null") + ", shallAddMenuButton: " + shallAddMenuButton);
		}
	}

	@Override
	protected ItemStack getItemAtSlot(MenuButton menuButton, int slot, int fillSlot) {
		if (menuButton == null) return null;
		List<Integer> fillSlots = getFillSpace();
		ItemStack result = null;

		if (fillSlots.contains(slot)) {
			MenuButtonPage<T> menuButtonPage = getPagedMenuButton(menuButton);
			T fillItem = getFillItem(fillSlot);

			if (menuButtonPage != null) {
				if (fillItem != null) result = menuButtonPage.getItem(fillItem);
				if (result == null) result = menuButtonPage.getItem(fillSlot, fillItem);
			}
		}
		if (result == null) result = menuButton.getItem();
		if (result == null) result = menuButton.getItem(fillSlot);
		logger.info("DEBUG: Get item at slot " + slot + ", result: " + (result != null ? result.getType() : "null"));
		return result;
	}

	@Nullable
	private MenuButtonPage<T> getPagedMenuButton(MenuButton menuButton) {
		return menuButton instanceof MenuButtonPage ? (MenuButtonPage<T>) menuButton : null;
	}

	private boolean isFillSlot(int slot) {
		List<Integer> fillSlots = getFillSpace();
		return !fillSlots.isEmpty() && fillSlots.contains(slot);
	}

	public Map<Integer, Map<Integer, ItemStack>> getMenuState() {
		Map<Integer, Map<Integer, ItemStack>> state = new HashMap<>();
		for (int page = 0; page < sharedPages.size(); page++) {
			Inventory inv = sharedPages.get(page);
			Map<Integer, ItemStack> slots = new HashMap<>();
			for (int slot : getFillSpace()) {
				ItemStack item = inv.getItem(slot);
				slots.put(slot, item);
				logger.info("DEBUG: Saving state for page " + page + ", slot " + slot + ", item: " + (item != null ? item.getType() : "null"));
			}
			if (!slots.isEmpty()) {
				state.put(page, slots);
			}
		}
		return state;
	}

	public String serializeItemStack(ItemStack item) {
		try {
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);
			dataOutput.writeObject(item);
			dataOutput.close();
			String serialized = Base64Coder.encodeLines(outputStream.toByteArray());
			logger.info("DEBUG: Serialized ItemStack: " + (item != null ? item.getType() : "null"));
			return serialized;
		} catch (IOException e) {
			logger.warning("Failed to serialize ItemStack: " + e.getMessage());
			return null;
		}
	}

	public ItemStack deserializeItemStack(String data) {
		try {
			ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64Coder.decodeLines(data));
			BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
			ItemStack item = (ItemStack) dataInput.readObject();
			dataInput.close();
			logger.info("DEBUG: Deserialized ItemStack: " + (item != null ? item.getType() : "null"));
			return item;
		} catch (IOException | ClassNotFoundException e) {
			logger.warning("Failed to deserialize ItemStack: " + e.getMessage());
			return null;
		}
	}

	public int getPlayerPage(Player player) {
		return playerMenuCache.getPlayerData(player).getCurrentPage() + 1;
	}

	public int getTotalPages() {
		return totalPages;
	}

	public List<Inventory> getSharedPages() {
		return sharedPages;
	}

	public boolean isSavePlayerPage() {
		return savePlayerPage;
	}

	public PlayerMenuCache getPlayerMenuCache() {
		return playerMenuCache;
	}

	public Map<Integer, Integer> getFillSlotsMapping() {
		return fillSlotsMapping;
	}

	@Nullable
	public FillItems<T> getListOfFillItem() {
		return listOfFillItems;
	}

	@Override
	@Nullable
	public List<T> getListOfFillItems() {
		if (getListOfFillItem() != null)
			return getListOfFillItem().getFillItems();
		return new ArrayList<>();
	}

	@Nullable
	public T getFillItem(int index) {
		FillItems<T> fillItems = getListOfFillItem();
		if (fillItems != null) {
			return fillItems.getFillItem(index);
		}
		return null;
	}
}