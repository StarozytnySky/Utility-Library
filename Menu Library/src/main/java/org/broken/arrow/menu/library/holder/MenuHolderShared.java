package org.broken.arrow.menu.library.holder;

import org.broken.arrow.menu.library.MenuMetadataKey;
import org.broken.arrow.menu.library.RegisterMenuAPI;
import org.broken.arrow.menu.library.builders.ButtonData;
import org.broken.arrow.menu.library.builders.MenuDataUtility;
import org.broken.arrow.menu.library.button.MenuButton;
import org.broken.arrow.menu.library.button.MenuButtonPage;
import org.broken.arrow.menu.library.button.logic.ButtonUpdateAction;
import org.broken.arrow.menu.library.button.logic.FillMenuButton;
import org.broken.arrow.menu.library.button.logic.OnRetrieveItem;
import org.broken.arrow.menu.library.cache.PlayerMenuCache;
import org.broken.arrow.menu.library.holder.utility.AnimateTitleTask;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
		setSlotsYouCanAddItems(true);
		this.amountOfPages();
		updateSharedPages();
		if (fillItems != null) {
			setFillItems(fillItems);
		}
	}

	public void setSavePlayerPage(boolean savePlayerPage) {
		this.savePlayerPage = savePlayerPage;
		logger.info("DEBUG: savePlayerPage set to " + savePlayerPage);
	}

	public void setFillItems(List<T> fillItems) {
		this.listOfFillItems = new FillItems<>();
		this.listOfFillItems.setFillItems(fillItems);

		distributeFillItems();
	}

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
		System.out.println("DEBUG: Distributed " + getListOfFillItem().getFillItems().size() + " items across " + totalPages + " pages");
	}

	private void updateSharedPages() {

		for (int i = 0; i < totalPages; i++) {
			Inventory inv = Bukkit.createInventory(null, inventorySize, "Shared Menu Page " + (i + 1));
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

	public void updateInventoryState(int page, int slot, @Nullable ItemStack item) {
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

	public abstract FillMenuButton<T> createFillMenuButton();

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

	public void setTitle(Player player, Function<String> function) {
		PlayerMenuCache.PlayerMenuData data = playerMenuCache.getPlayerData(player);
		data.setTitleFunction(function);
		String title = function.apply();
		if (title != null && !title.isEmpty()) {
			updateTitle(player, title);
			logger.info("DEBUG: Set title for player " + player.getName() + ": " + title);
		}
	}

	public void setAnimateTitle(Player player, int time, Function<String> function) {
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
		logger.info("DEBUG: Set animate title for player " + player.getName() + ", time: " + time + ", taskId: " + newTask.getTaskId());
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
					System.out.println("Put button item " + item.getType().name());
					System.out.println("Put button value " + button.getId());
					menuData.putButton(slot, new ButtonData<>(item, button, null));
					logger.info("DEBUG: Set and cached button at slot " + slot + ", item: " + item.getType() + ", page: " + sharedPages.indexOf(inventory));
				} else {
					logger.info("DEBUG: Null item for button at slot " + slot + ", page: " + sharedPages.indexOf(inventory));
				}
			}
		}
		putAddedButtonsCache(sharedPages.indexOf(inventory), menuData);
	}

	@Override
	public void menuOpen(@Nonnull Player player, @Nullable Location location) {
		player.closeInventory();

		redrawInventory(true);

		Inventory inventory1 = loadInventory(player,location,true);

		if (inventory1 == null) return;

		PlayerMenuCache.PlayerMenuData data = playerMenuCache.getPlayerData(player);


		int page = savePlayerPage ? data.getCurrentPage() : 0;
		data.setCurrentPage(page);
		inventory1 = sharedPages.get(page);
		player.openInventory(inventory1);

		onMenuOpenPlaySound(player);

		if (!getButtonsToUpdate().isEmpty())
			updateButtonsInList();
		//getPlayerMenuMetadata

		menuAPI.getPlayerMeta().setPlayerMenuMetadata(player, MenuMetadataKey.MENU_OPEN, this);

		//Bukkit.getScheduler().runTaskLater(menuAPI.getPlugin(), ()-> this.updateTitle(), 1);

		logger.info("DEBUG: Menu opened for player " + player.getName() + " on page " + page + ", metadata set");
	}


	public void changePage(Player player, boolean nextPage, boolean circle) {
		PlayerMenuCache.PlayerMenuData data = playerMenuCache.getPlayerData(player);
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

			if (data.getAnimateTitleFunction() != null) {
				setAnimateTitle(player, data.getAnimateTitleTime(), data.getAnimateTitleFunction());
			} else {
				Function<String> titleFunction = data.getTitleFunction();
				updateTitle(player, titleFunction != null ? titleFunction.apply() : getTitle());
			}

			menuAPI.getPlayerMeta().setPlayerMenuMetadata(player, MenuMetadataKey.MENU_OPEN, this);

			logger.info("DEBUG: Changed to page " + newPage + " for player " + player.getName());
		}, 1);
	}

	@Override
	public void onClick(@Nonnull MenuButton menuButton, @Nonnull Player player, int clickedPos, @Nonnull ClickType clickType, @Nonnull ItemStack clickedItem) {
		int slot = fillSlotsMapping.getOrDefault(clickedPos, clickedPos);
		logger.info("DEBUG: Clicked slot " + clickedPos + " (mapped to " + slot + ") by " + player.getName() + ", button: " + (menuButton != null ? menuButton.getClass().getSimpleName() : "null"));
		if (getMenu() != null && menuButton != null) {
			menuAPI.getPlayerMeta().setPlayerMetadata(player, MenuMetadataKey.NAVIGATION_CLICK.name(), true);
			try {
				if (menuButton instanceof MenuButtonPage) {
					T object = getFillItem(slot);
					((MenuButtonPage<T>) menuButton).onClickInsideMenu(player, getMenu(), clickType, clickedItem, object);
				} else {
					menuButton.onClickInsideMenu(player, getMenu(), clickType, clickedItem);
				}
			} finally {
				menuAPI.getPlayerMeta().removePlayerMenuMetadata(player, MenuMetadataKey.NAVIGATION_CLICK);
			}
		} else {
			logger.info("DEBUG: No button or menu for slot " + clickedPos + ", player: " + player.getName());
		}
	}

	@Override
	public void menuClose(Player player, InventoryCloseEvent event) {
		System.out.println("DEBUG: Closed menu for player " + player.getName() + ", metadata removed");
		playerMenuCache.removePlayerData(player);
		AnimateTitleTask<T> playerTask = titleTasks.get(player);
		if (playerTask != null && playerTask.isRunning()) {
			playerTask.stopTask();
			titleTasks.remove(player);
		}
		menuAPI.getPlayerMeta().removePlayerMenuMetadata(player, MenuMetadataKey.MENU_OPEN);
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
			// Wzoruj się na MenuHolderPage: null dla fillSlots, menuButton dla getButtonAt
			boolean shallAddMenuButton = !isLastFillSlot && isFillSlot(slot) && this.getListOfFillItems() != null && !this.getListOfFillItems().isEmpty();
			if (menuButton.shouldUpdateButtons()) this.getButtonsToUpdate().add(menuButton);

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