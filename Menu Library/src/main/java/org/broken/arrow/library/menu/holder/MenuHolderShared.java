package org.broken.arrow.library.menu.holder;

import org.broken.arrow.library.menu.MenuUtility;
import org.broken.arrow.library.menu.builders.ButtonData;
import org.broken.arrow.library.menu.builders.MenuDataUtility;
import org.broken.arrow.library.menu.button.MenuButton;
import org.broken.arrow.library.menu.button.MenuButtonPage;
import org.broken.arrow.library.menu.button.logic.*;
import org.broken.arrow.library.menu.cache.PlayerMenuCache;
import org.broken.arrow.library.menu.runnable.AnimateTitleTask;
import org.broken.arrow.library.menu.runnable.ButtonAnimation;
import org.broken.arrow.library.menu.utility.Action;
import org.broken.arrow.library.menu.utility.FillItems;
import org.broken.arrow.library.menu.utility.MetadataPlayer;
import org.broken.arrow.library.menu.utility.metadata.MenuMetaKeyIdentifier;
import org.broken.arrow.library.menu.utility.metadata.MenuMetadataKey;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;

public abstract class MenuHolderShared<T> extends HolderUtility<T> {
	private final Logger logger = Logger.getLogger(MenuHolderShared.class.getName());

	private final Map<Integer, Integer> fillSlotsMapping = new HashMap<>();
	private FillSlotInteractionHandler<T> fillSlotInteractionHandler;

	private final List<Inventory> sharedPages = new ArrayList<>();
	private final PlayerMenuCache<T> playerMenuCache = new PlayerMenuCache<T>();
	private final int totalPages;
	private final MenuMetaKeyIdentifier menuMetadataKey = new MenuMetaKeyIdentifier(MenuMetadataKey.CUSTOM, 1);
	private final MetadataPlayer playerMeta;
	private FillItems<T> listOfFillItems;
	private boolean savePlayerPage = true;
	private boolean neeedUpdateFill = true;


	/**
	 * The size of each inventory in the menu.
	 * @param totalPages the total number of pages in the menu
	 * @param inventorySize the size of each inventory (must be a multiple of 9)
	 */
	protected MenuHolderShared(int totalPages, int inventorySize) {
		this(totalPages, inventorySize, null);
	}

	/**
	 * The size of each inventory in the menu.
	 * @param totalPages the total number of pages in the menu
	 * @param inventorySize the size of each inventory (must be a multiple of 9)
	 * @param fillItems the list of items to fill the menu with
	 */
	protected MenuHolderShared(int totalPages, int inventorySize, @Nullable List<T> fillItems) {
		this(totalPages, inventorySize, null, fillItems);
	}

	/**
	 * The size of each inventory in the menu.
	 * @param totalPages the total number of pages in the menu
	 * @param inventorySize the size of each inventory (must be a multiple of 9)
	 * @param fillSlots the list of slots to fill in each inventory
	 * @param fillItems the list of items to fill the menu with
	 */
	protected MenuHolderShared(int totalPages, int inventorySize, @Nullable List<Integer> fillSlots, @Nullable List<T> fillItems) {
		this(totalPages, inventorySize, fillSlots, fillItems, true);
	}


	/**
	 * The size of each inventory in the menu.
	 * @param totalPages the total number of pages in the menu
	 * @param inventorySize the size of each inventory (must be a multiple of 9)
	 * @param fillSlots the list of slots to fill in each inventory
	 * @param fillItems the list of items to fill the menu with
	 * @param shallCacheItems whether to cache items for performance
	 */
	protected MenuHolderShared(int totalPages, int inventorySize, @Nullable List<Integer> fillSlots, @Nullable List<T> fillItems, boolean shallCacheItems) {
		super(fillSlots, shallCacheItems);
		this.totalPages = totalPages;
		this.inventorySize = inventorySize;
		//this.setSlotsYouCanAddItems(true);
		this.amountOfPages();
		this.updateSharedPages();
		this.setFillItems(fillItems);
		this.playerMeta = menuAPI.getPlayerMeta();
	}

	/**
	 * Set fill slot interaction handler
	 * @param fillItems the list of items to fill the menu with
	 */
	public void setFillItems(List<T> fillItems) {
		if (fillItems != null) {
			this.listOfFillItems = new FillItems<>();
			this.listOfFillItems.setFillItems(fillItems);
			this.neeedUpdateFill = true;
		}
	}

	private void distributeFillItems() {
		List<Integer> fillSlots = getFillSpace();
		int itemsPerPage = fillSlots.size();
		final List<T> fillItems = getListOfFillItem() != null ? getListOfFillItem().getFillItems() : new ArrayList<>();

		FillMenuButton<T> fillMenuButton = createFillMenuButton();

		for (int page = 0; page < totalPages; page++) {
			Inventory inv = sharedPages.get(page);
			MenuDataUtility<T> menuData = getMenuData(null, page);
			if (menuData == null) {
				menuData = new MenuDataUtility<>();
				putAddedButtonsCache(page, menuData);
			}

			if (itemsPerPage > 0) {
				for (int i = 0; i < itemsPerPage; i++) {
					int itemIndex = page * itemsPerPage + i;
					int slot = fillSlots.get(i);

					T fillItem = getListOfFillItem() != null ? getListOfFillItem().getFillItem(itemIndex) : null;
					ItemStack itemStack;

					if (fillMenuButton != null && fillMenuButton.getMenuFillItem() != null) {
						itemStack = fillMenuButton.getMenuFillItem().apply(slot, fillItem);
					} else {
						itemStack = null;
					}

					inv.setItem(slot, itemStack);

					// Dodaj przycisk do cache z obiektem fill
					if (fillItem != null && fillMenuButton != null) {
						final MenuButton menuButton = getButtonAt(itemIndex);
						ButtonData<T> buttonData = new ButtonData<>(itemStack, menuButton, fillItem);
						menuData.putButton(slot, buttonData, new MenuButton() {
							@Override
							public void onClickInsideMenu(Player player, Inventory menu, ClickType click, ItemStack clickedItem) {
								if (fillMenuButton.getClick() != null) {
									fillMenuButton.getClick().apply(player, menu, click, clickedItem, fillItem);
								}
							}

							@Override
							public ItemStack getItem() {
								return itemStack;
							}
						});
					}
				}
			}
			setButtons(inv);
		}


		if (!fillItems.isEmpty())
			System.out.println("DEBUG: Distributed " + fillItems.size() + " items across " + totalPages + " pages");
		System.out.println("DEBUG: Distributed 0 " + " items across " + totalPages + " pages");
	}
	private void updateSharedPages() {

		for (int i = 0; i < totalPages; i++) {
			Inventory inv = Bukkit.createInventory(null, inventorySize, "Shared Menu Page: " + (i + 1));
			sharedPages.add(inv);
		}

//		sharedPages.clear();
//		if (listOfFillItems != null && totalPages == 0) {
//			totalPages = (int) Math.max(Math.ceil(getMenuRenderer().getSetPages()), 1);
//		}
//		for (int i = 0; i < totalPages; i++) {
//			Inventory inv = Bukkit.createInventory(null, inventorySize, "Shared Menu Page " + (i + 1));
//			sharedPages.add(inv);
//			logger.info("DEBUG: Created inventory for page " + i + ", size: " + inventorySize);
//		}
	}

	@Override
	public boolean checkValidMenu(@Nonnull final Inventory topInventory, @Nonnull final Action action) {
		if (action == Action.OPEN)
			return true;

		return sharedPages.contains(topInventory);
	}

	/**
	 * Update the inventory state for a specific page and slot.
	 * @param page the page number to update
	 * @param slot the slot number to update
	 * @param item the new item to set in the slot (null to clear the slot)
	 */
	public void updateInventoryState(int page, int slot, @Nullable ItemStack item) {
		if (page < sharedPages.size()) {
			Inventory inv = sharedPages.get(page);
			inv.setItem(slot, item);
			MenuDataUtility<T> menuData = getMenuData(null, page);
			if (menuData != null) {
				ButtonData<T> buttonData = menuData.getButton(slot);
				if (buttonData != null) {
					menuData.putButton(slot, new ButtonData<>(item, buttonData.getMenuButton(), buttonData.getObject()));
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
				} else if (fillItems != null && !fillItems.isEmpty())
					return (double) fillItems.size() / this.itemsPerPage;
			}
			if (fillItems != null && !fillItems.isEmpty()) {
				return (double) fillItems.size() / (fillSlots.isEmpty() ? this.inventorySize - 9 : fillSlots.size());
			}
			return (double) getManuallySetPages();
		});
	}

	private void setMenuItemsToAllPages() {
		getMenuRenderer().setAmountOfPages(() -> (Math.max((double) getTotalPages(), 1)));
		if (this.getManuallySetPages() > 0)
			getMenuRenderer().setAmountOfPages(() -> (double) getManuallySetPages());
		for (int i = 0; i < this.getRequiredPages(); i++) {
			getMenuRenderer().setMenuItemsToPage(i);
			if (i == 0)
				getMenuRenderer().setHighestFillSlot(this.slotIndex);
		}
		this.slotIndex = 0;
		logger.info("DEBUG: Rendered fill items for all pages");
	}

	/**
	 * Get the list of fill items.
	 * @return the FillItems object containing the list of fill items
	 */
	public abstract FillMenuButton<T> createFillMenuButton();

	/**
	 * Get the list of fill items.
	 * @param slot the slot number to register the button in.
	 * @return the MenuButton for the specified slot, or null if no button is set.
	 */
	@Nullable
	@Override
	public MenuButton getFillButtonAt(int slot) {
		if (slot == -1) return null;

		FillMenuButton<T> fillMenuButton = createFillMenuButton();
		if (fillMenuButton != null) return new MenuButtonPage<T>() {
			@Override
			public void onClickInsideMenu(@Nonnull Player player, @Nonnull Inventory menu, @Nonnull ClickType click, @Nonnull ItemStack clickedItem, @Nullable T fillItem) {
				ButtonUpdateAction buttonUpdateAction = fillMenuButton.getClick().apply(player, menu, click, clickedItem, fillItem);
				switch (buttonUpdateAction) {
					case ALL:
						updateButtons();
						break;
					case THIS:
						updateButton(this);
						break;
					case NONE:
						break;
				}
			}

			@Override
			public long setUpdateTime() {
				return fillMenuButton.getUpdateTime();
			}

			@Override
			public boolean shouldUpdateButtons() {
				return fillMenuButton.isUpdateButtonsTimer();
			}

			@Override
			public ItemStack getItem(int slot, @Nullable T fillItem) {
				OnRetrieveItem<ItemStack, Integer, T> menuItem = fillMenuButton.getMenuFillItem();
				return menuItem.apply(slot, fillItem);
			}
		};
		return null;
	}

	/**
	 * Get the list of fill items.
	 * @param player the player to get the fill items for
	 * @param function a supplier function that provides the list of fill items
	 */
	public void setTitle(Player player, Supplier<String> function) {
		PlayerMenuCache.PlayerMenuData<T> data = getPlayerMenuCache().getPlayerData(player);
		data.setTitleFunction(function);
		String title = function.get();
		if (title != null && !title.isEmpty()) {
			updateTitle(player, title);
			logger.info("DEBUG: Set title for player " + player.getName() + ": " + title);
		}
	}

	/**
	 * Set animated title for the player.
	 * @param player the player to set the animated title for
	 * @param time the time interval in ticks for updating the title
	 * @param function a supplier function that provides the title string
	 */
	public void setAnimateTitle(Player player, int time, Supplier<String> function) {
		//this.setAnimateTitle((int) (20L + time), function);

		PlayerMenuCache.PlayerMenuData<T> data = getPlayerMenuCache().getPlayerData(player);
		data.setAnimateTitleFunction(function);
		AnimateTitleTask<T> existingTask = data.getAnimateTitleTask();
		if (existingTask != null && existingTask.isRunning()) {
			existingTask.stopTask();
		}

		AnimateTitleTask<T> newTask = new AnimateTitleTask<>(function,this, player);
		newTask.runTask(20L + time);
		data.setAnimateTitleTask(newTask);
		logger.info("DEBUG: Set animate title for player " + player.getName() + ", time: " + time + ", taskId: " + newTask.getTaskId());
	}

	private void setButtons(Inventory inventory) {
		final int page = sharedPages.indexOf(inventory);
		MenuDataUtility<T> menuData = getMenuData(null, page);
		if (menuData == null) {
			menuData = new MenuDataUtility<>();
			putAddedButtonsCache(page, menuData);
		}
		for (int slot = 0; slot < inventory.getSize(); slot++) {
			MenuButton button = getButtonAt(slot);
			if (button != null) {
				ItemStack item = button.getItem();
				if (item != null) {
					inventory.setItem(slot, item);
					System.out.println("Put button item " + item.getType().name());
					System.out.println("Put button value " + button.getId());
					menuData.putButton(slot, new ButtonData<>(item, button, null));
					logger.info("DEBUG: Set and cached button at slot " + slot + ", item: " + item.getType() + ", page: " + page);
				} else {
					logger.info("DEBUG: Null item for button at slot " + slot + ", page: " + page);
				}
			}
		}
		putAddedButtonsCache(page, menuData);
	}

	@Override
	public void menuOpen(@Nonnull Player player, @Nullable Location location) {
		player.closeInventory();
		this.player = player;

		if (this.neeedUpdateFill) {
			this.distributeFillItems();
			this.neeedUpdateFill = false;
		}

		PlayerMenuCache.PlayerMenuData<T> data = playerMenuCache.getPlayerData(player);


		int page = savePlayerPage ? data.getCurrentPage() : 0;
		data.setCurrentPage(page);
		Inventory menu = sharedPages.get(page);
		final Map<Integer, ButtonData<T>> buttonsToUpdate = this.getButtonsToUpdate(page);
		player.openInventory(menu);
		onMenuOpenPlaySound(player);

		if (!buttonsToUpdate.isEmpty()) {
			System.out.println("DEBUG: Updating buttons for player " + player.getName() + " on page " + page);
			startPlayerButtonAnimation(player);
		} else {
			System.out.println("DEBUG: No buttons to update for player " + player.getName() + " on page " + page);
		}

		if (!buttonsToUpdate.isEmpty())
			updateButtonsInList();

		//runAnimateTitle();
		//getPlayerMenuMetadata

		this.playerMeta.setPlayerMenuMetadata(player, MenuMetadataKey.MENU_OPEN, this);

		//Bukkit.getScheduler().runTaskLater(menuAPI.getPlugin(), () -> this.updateTitle(), 1);
		logger.info("DEBUG: Menu opened for player " + player.getName() + " on page " + page + ", metadata set");
		logger.info("DEBUG: Menu instance " + menu);
	}

	/**
	 * Change the current page for the player.
	 * @param player the player whose page is to be changed
	 * @param nextPage true to go to the next page, false to go to the previous page
	 * @param circle true to wrap around when reaching the end or beginning of pages
	 */
	public void changePage(Player player, boolean nextPage, boolean circle) {
		PlayerMenuCache.PlayerMenuData<T> data = getPlayerMenuCache().getPlayerData(player);
		int currentPage = data.getCurrentPage();
		int newPage;
		if (circle && totalPages > 1) {
			newPage = nextPage ? (currentPage + 1) % totalPages : (currentPage - 1 + totalPages) % totalPages;
		} else {
			newPage = nextPage ? Math.min(currentPage + 1, totalPages - 1) : Math.max(currentPage - 1, 0);
		}
		data.setCurrentPage(newPage);
		player.closeInventory();

		Bukkit.getScheduler().runTaskLater(getPlugin(), () -> {
			Inventory inv = sharedPages.get(newPage);
			player.openInventory(inv);

			final Supplier<String> animateTitle = data.getAnimateTitleFunction();
			if (animateTitle != null) {
				setAnimateTitle(player, this.getUpdateTime(), animateTitle);
			} else {
				Supplier<String> titleFunction = data.getTitleFunction();
				updateTitle(player, titleFunction != null ? titleFunction.get() : getTitle());
			}
			this.playerMeta.setPlayerMenuMetadata(player, MenuMetadataKey.MENU_OPEN, this);

			logger.info("DEBUG: Changed to page " + newPage + " for player " + player.getName());
		}, 1);
	}

	@Override
	public void onClick(@Nonnull MenuButton menuButton, @Nonnull Player player, int clickedPos, @Nonnull ClickType clickType, @Nonnull ItemStack clickedItem) {
		int slot = fillSlotsMapping.getOrDefault(clickedPos, clickedPos);
		logger.info("DEBUG: Clicked slot " + clickedPos + " (mapped to " + slot + ") by " + player.getName() + ", button: " + (menuButton != null ? menuButton.getClass().getSimpleName() : "null"));
		if (menuButton != null) {
			this.playerMeta.setPlayerMetaKey(player, this.menuMetadataKey);
			try {
				final Inventory menu = sharedPages.get(getPageNumber());
				if (menuButton instanceof MenuButtonPage) {
					T object = getFillItem(slot);
					((MenuButtonPage<T>) menuButton).onClickInsideMenu(player, menu, clickType, clickedItem, object);
				} else {
					menuButton.onClickInsideMenu(player, menu, clickType, clickedItem);
				}
			} finally {
				this.playerMeta.removePlayerMenuMetadata(player, this.menuMetadataKey);
			}
		} else {
			logger.info("DEBUG: No button or menu for slot " + clickedPos + ", player: " + player.getName());
		}
	}

	@Override
	public void menuClose(final InventoryCloseEvent event, final MenuUtility<?> menu) {
		Player player = (Player) event.getPlayer();

		boolean hasMetadata = playerMeta.hasPlayerMetadata(player, menuMetadataKey);
		if (hasMetadata)
			return;

		System.out.println("DEBUG: Closed menu for player " + player.getName() + ", metadata removed");

		playerMenuCache.removePlayerData(player);
		this.playerMeta.removePlayerMenuMetadata(player, MenuMetadataKey.MENU_OPEN);
	}

	@Override
	protected void unregister(@Nonnull final Player player) {
		this.closeTasks();
		boolean hasMetadata = playerMeta.hasPlayerMetadata(player, menuMetadataKey);
		if (hasMetadata)
			return;
		this.playerMeta.removePlayerMenuMetadata(player, MenuMetadataKey.MENU_OPEN);
	}

	@Override
	public void setButton(int pageNumber, MenuDataUtility<T> menuDataUtility, int slot, int fillSlotIndex, boolean isLastFillSlot) {
		int fillSlot = isLastFillSlot ? -1 : fillSlotIndex;

		final MenuButton menuButton = getMenuButtonAtSlot(slot, fillSlot, true);
		final ItemStack result = getItemAtSlot(menuButton, slot, fillSlot, true);

		if (pageNumber == getPageNumber() && fillSlot >= 0) {
			this.fillSlotsMapping.put(slot, fillSlot);
		}

		if (menuButton != null) {
			T fillItem = getFillItem(fillSlot);
			boolean shallAddMenuButton = !isLastFillSlot && isFillSlot(slot) && this.getListOfFillItems() != null && !this.getListOfFillItems().isEmpty();
			menuDataUtility.putButton(slot, menuButton, buttonDataWrapper -> buttonDataWrapper
					.setItemStack(result)
					.setFillButton(shallAddMenuButton)
					.setObject(fillItem));
			logger.info("DEBUG: Setting ButtonData for slot " + slot + ", item: " + (result != null ? result.getType() : "null") + ", menuButton: " + (menuButton != null ? menuButton.getClass().getSimpleName() : "null") + ", shallAddMenuButton: " + shallAddMenuButton);
		}
	}

	@Override
	protected ItemStack getItemAtSlot(MenuButton menuButton, int slot, int fillSlot, final boolean isFillSlot) {
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

	/**
	 * Get the paged menu button if the given button is an instance of MenuButtonPage.
	 * @param menuButton the MenuButton to check
	 * @return the MenuButtonPage if the button is an instance of it, null otherwise
	 */
	@Nullable
	private MenuButtonPage<T> getPagedMenuButton(MenuButton menuButton) {
		return menuButton instanceof MenuButtonPage ? (MenuButtonPage<T>) menuButton : null;
	}

	/**
	 * Check if the given slot is a fill slot.
	 * @param slot the slot index to check
	 * @return true if the slot is a fill slot, false otherwise
	 */
	private boolean isFillSlot(int slot) {
		List<Integer> fillSlots = getFillSpace();
		return !fillSlots.isEmpty() && fillSlots.contains(slot);
	}

	/**
	 * Het menu state as a map.
	 * @return a map where the key is the page number and the value is another map of slot indices to ItemStacks
	 */
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

	/**
	 * Restore the menu state from the given map.
	 * @param state a map where the key is the page number and the value is another map of slot indices to ItemStacks
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

	/**
	 * Start button animation task for the player.
	 * @param player the player to start the animation for
	 */
	public void startPlayerButtonAnimation(Player player) {
		final int page = getPlayerPage(player) - 1;
		final Map<Integer, ButtonData<T>> buttonsToUpdate = this.getButtonsToUpdate(page);
		if (buttonsToUpdate.isEmpty() || this.getUpdateTime() == -1) {
			logger.info("DEBUG: could not button animation: " + buttonsToUpdate);
			return;
		}

		PlayerMenuCache.PlayerMenuData<T> playerData = getPlayerMenuCache().getPlayerData(player);

		// Stop existing animation if any
		ButtonAnimation<T> existingAnimation = playerData.getButtonAnimation();
		if (existingAnimation != null && existingAnimation.isRunning()) {
			existingAnimation.stopTask();
		}

		// Create and start new animation
		ButtonAnimation<T> newAnimation = new ButtonAnimation<>(this);
		playerData.setButtonAnimation(newAnimation);
		newAnimation.runTask(this.animateButtonTime);
		newAnimation.setDataForAnimation(() -> {
			final int currentPage = getPlayerPage(player) - 1;
			return new ButtonAnimationData(this.sharedPages.get(currentPage), currentPage);
		});
		System.out.println("DEBUG: Started PlayerButtonAnimation for player " + player.getName());
	}

	@Nonnull
	private Map<Integer, ButtonData<T>> getButtonsToUpdate(final int page) {
		final MenuDataUtility<T> menuData = getMenuData(null, page);
		if (menuData == null) {
			return new HashMap<>();
		}
		return menuData.getButtonsToUpdate();
	}

	/**
	 * Serialize an ItemStack to a Base64-encoded string.
	 * @param item the ItemStack to serialize
	 * @return a Base64 string representing the serialized ItemStack, or null if serialization fails
	 */
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

	/**
	 * Deserialize an ItemStack from a Base64-encoded string.
	 * @param data the Base64 string representing the serialized ItemStack
	 * @return the deserialized ItemStack, or null if deserialization fails
	 */
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

	/**
	 * Get the current page number for the player (1-based index).
	 * @param player the player whose page number to get
	 * @return the current page number (1-based index)
	 */
	public int getPlayerPage(Player player) {
		return getPlayerMenuCache().getPlayerData(player).getCurrentPage() + 1;
	}

	/**
	 * Get the total number of pages in the menu.
	 * @return the total number of pages
	 */
	public int getTotalPages() {
		return totalPages;
	}

	/**
	 * Get the list of shared inventory pages.
	 * @return the list of shared inventory pages
	 */
	public List<Inventory> getSharedPages() {
		return sharedPages;
	}

	/**
	 * Check whether to save the player's current page when they close and reopen the menu.
	 * @return true if the player's page is saved, false to always open on page 1
	 */
	public boolean isSavePlayerPage() {
		return savePlayerPage;
	}

	/**
	 * Set whether to save the player's current page when they close and reopen the menu.
	 * @param savePlayerPage true to save the player's page, false to always open on page 1
	 */
	public void setSavePlayerPage(boolean savePlayerPage) {
		this.savePlayerPage = savePlayerPage;
		logger.info("DEBUG: savePlayerPage set to " + savePlayerPage);
	}

	/**
	 * Get the player menu cache.
	 * @return the player menu cache
	 */
	public PlayerMenuCache<T> getPlayerMenuCache() {
		return playerMenuCache;
	}

	/**
	 * Get the mapping of fill slots to actual inventory slots.
	 * @return a map where the key is the inventory slot and the value is the corresponding fill slot
	 */
	public Map<Integer, Integer> getFillSlotsMapping() {
		return fillSlotsMapping;
	}

	/**
	 * Get the list of fill items.
	 * @return the FillItems object containing the list of fill items, or null if not set
	 */
	@Nullable
	public FillItems<T> getListOfFillItem() {
		return listOfFillItems;
	}

	/**
	 * Get the list of fill items.
	 * @return the list of fill items, or an empty list if none are set
	 */
	@Override
	@Nullable
	public List<T> getListOfFillItems() {
		if (getListOfFillItem() != null)
			return getListOfFillItem().getFillItems();
		return new ArrayList<>();
	}

	/**
	 * Get fill item at specified index.
	 * @param index the index of the fill item
	 * @return the fill item at the specified index, or null if not found
	 */
	@Nullable
	public T getFillItem(int index) {
		FillItems<T> fillItems = getListOfFillItem();
		if (fillItems != null) {
			return fillItems.getFillItem(index);
		}
		return null;
	}

	/**
	 * Set fill slot interaction handler
	 * @param handler the fill slot interaction handler
	 */
	public void setFillSlotInteractionHandler(FillSlotInteractionHandler<T> handler) {
		this.fillSlotInteractionHandler = handler;
	}

	/**
	 * Get fill slot interaction handler
	 * @return the fill slot interaction handler, or null if not set
	 */
	@Nullable
	public FillSlotInteractionHandler<T> getFillSlotInteractionHandler() {
		return fillSlotInteractionHandler;
	}
}
