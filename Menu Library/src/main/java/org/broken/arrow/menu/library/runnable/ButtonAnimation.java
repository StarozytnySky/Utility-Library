package org.broken.arrow.menu.library.runnable;

import org.broken.arrow.menu.library.MenuUtility;
import org.broken.arrow.menu.library.builders.ButtonData;
import org.broken.arrow.menu.library.builders.MenuDataUtility;
import org.broken.arrow.menu.library.button.MenuButton;
import org.broken.arrow.menu.library.cache.PlayerMenuCache;
import org.broken.arrow.menu.library.holder.MenuHolderShared;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

public class ButtonAnimation<T> extends BukkitRunnable {
    private final Map<Integer, Long> timeWhenUpdatesButtons = new HashMap<>();
    private Player player;
    private final MenuUtility<T> menuUtility;
    private final Inventory menu;
    private final int inventorySize;
    private int counter = 0;
    private int taskId;

    public ButtonAnimation(MenuUtility<T> menuUtility) {
        this(null, menuUtility);
    }

    public ButtonAnimation(@Nullable Player player, MenuUtility<T> menuUtility) {
        this.player = player;
        this.menuUtility = menuUtility;
        this.menu = menuUtility.getMenu();
        this.inventorySize = menuUtility.getInventorySize();
    }

    public void runTask(long delay) {
        taskId = runTaskTimer(menuUtility.getPlugin(), 1L, delay).getTaskId();
        System.out.println("DEBUG: Started ButtonAnimation with delay " + delay);
    }

    public boolean isRunning() {
        return taskId > 0 &&
                (Bukkit.getScheduler().isCurrentlyRunning(taskId) ||
                        Bukkit.getScheduler().isQueued(taskId));
    }

    public void stopTask() {
        if (this.isRunning()) {
            Bukkit.getScheduler().cancelTask(this.taskId);
            System.out.println("DEBUG: Stopped ButtonAnimation");
        }
    }

    @Override
    public void run() {
        if (menuUtility.getButtonsToUpdate().isEmpty()) {
            System.out.println("DEBUG: No buttons to update, stopping task");
            cancel();
            return;
        }
        for (final MenuButton menuButton : menuUtility.getButtonsToUpdate()) {
            ItemStack item = menuButton.getItem();
            System.out.println("DEBUG: Update button task, button ID: " + menuButton.getId() + ", class: " + menuButton.getClass().getSimpleName() + ", item: " + (item != null ? item.getType() : "null"));

            final Long timeLeft = getTimeWhenUpdatesButton(menuButton);
            if (timeLeft != null && timeLeft == -1) {
                System.out.println("DEBUG: Button ID " + menuButton.getId() + " has update time -1, skipping");
                continue;
            }

            if (timeLeft == null || timeLeft == 0) {
                putTimeWhenUpdatesButtons(menuButton, counter + getTime(menuButton));
            } else if (counter >= timeLeft) {
                int pageNumber;
                Inventory targetInventory = menu;

                if (menuUtility instanceof MenuHolderShared && player != null) {
                    MenuHolderShared<?> sharedMenu = (MenuHolderShared<?>) menuUtility;
                    PlayerMenuCache.PlayerMenuData cacheData = sharedMenu.getPlayerMenuCache().getPlayerData(player);
                    pageNumber = cacheData.getCurrentPage();
                    targetInventory = cacheData.getInventory();
                    System.out.println("DEBUG: Shared menu, page: " + pageNumber + ", inventory: " + (targetInventory != null ? targetInventory.hashCode() : "null"));
                } else {
                    pageNumber = menuUtility.getPageNumber();
                }
                System.out.println("DEBUG: Updating button ID " + menuButton.getId() + " on page " + pageNumber);
                final MenuDataUtility<T> menuDataUtility = menuUtility.getMenuData(pageNumber);
                if (menuDataUtility == null) {
                    System.out.println("DEBUG: MenuDataUtility is null for page " + pageNumber + ", stopping task");
                    cancel();
                    return;
                }
                final Set<Integer> itemSlots = getItemSlotsMap(menuDataUtility, menuButton);
                System.out.println("DEBUG: Found " + itemSlots.size() + " slots for button ID " + menuButton.getId());
                if (updateButtonsData(menuButton, menuDataUtility, itemSlots, targetInventory)) return;
            }
        }
        counter++;
    }

    private boolean updateButtonsData(final MenuButton menuButton, final MenuDataUtility<T> menuDataUtility, final Set<Integer> itemSlots, Inventory targetInventory) {
        if (!itemSlots.isEmpty()) {
            final Iterator<Integer> slotList = itemSlots.iterator();
            setButtons(menuButton, menuDataUtility, slotList, targetInventory);
        }
        putTimeWhenUpdatesButtons(menuButton, counter + getTime(menuButton));
        return false;
    }

    private void setButtons(final MenuButton menuButton, final MenuDataUtility<T> menuDataUtility, final Iterator<Integer> slotList, Inventory targetInventory) {
        if (targetInventory == null) {
            System.out.println("DEBUG: setButtons targetInventory is null, skipping button update for button ID " + menuButton.getId());
            return;
        }

        while (slotList.hasNext()) {
            final Integer slot = slotList.next();
            int slotPageCalculated = menuUtility.getSlot(slot);
            final ButtonData<T> buttonData = menuDataUtility.getButton(slotPageCalculated);
            if (buttonData == null) {
                System.out.println("DEBUG: ButtonData is null for slot " + slotPageCalculated + ", skipping");
                continue;
            }

            final ItemStack menuItem = getMenuItemStack(menuButton, buttonData, slot);
            if (menuItem == null) {
                System.out.println("DEBUG: Menu item is null for slot " + slot + ", button ID " + menuButton.getId());
                continue;
            }
            final ButtonData<T> newButtonData = buttonData.copy(menuItem);

            menuDataUtility.putButton(slotPageCalculated, newButtonData);
            targetInventory.setItem(slot, menuItem);
            System.out.println("DEBUG: Updated slot " + slot + " with item " + menuItem.getType() + " for button ID " + menuButton.getId());
            slotList.remove();
        }
    }

    @Nonnull
    protected Map<Integer, Long> getTimeWhenUpdatesButtons() {
        return timeWhenUpdatesButtons;
    }

    @Nullable
    public Long getTimeWhenUpdatesButton(final MenuButton menuButton) {
        return getTimeWhenUpdatesButtons().getOrDefault(menuButton.getId(), null);
    }

    protected void putTimeWhenUpdatesButtons(final MenuButton menuButton, final Long time) {
        this.getTimeWhenUpdatesButtons().put(menuButton.getId(), time);
    }

    private long getTime(final MenuButton menuButton) {
        long time = menuButton.setUpdateTime();
        if (time == -1) time = this.menuUtility.getUpdateTime();
        System.out.println("DEBUG: Update time for button ID " + menuButton.getId() + ": " + time);
        return time;
    }

    @Nullable
    private ItemStack getMenuItemStack(final MenuButton menuButton, final ButtonData<T> cachedButtons, final int slot) {
        ItemStack item = this.menuUtility.getMenuItem(menuButton, cachedButtons, slot, menuButton.shouldUpdateButtons());
        System.out.println("DEBUG: Getting menu item for slot " + slot + ", button ID " + menuButton.getId() + ": " + (item != null ? item.getType() : "null"));
        return item;
    }

    @Nonnull
    private Set<Integer> getItemSlotsMap(final MenuDataUtility<T> menuDataMap, final MenuButton menuButton) {
        final Set<Integer> slotList = new HashSet<>();
        if (menuDataMap == null) return slotList;

        for (int slot = 0; slot < inventorySize; slot++) {
            int menuSlot = this.menuUtility.getSlot(slot);
            final ButtonData<T> addedButtons = menuDataMap.getButtons().get(menuSlot);
            if (addedButtons == null) continue;

            final MenuButton cacheMenuButton = addedButtons.getMenuButton();
            final MenuButton fillMenuButton = menuDataMap.getFillMenuButton(menuSlot);
            final int menuButtonId = menuButton.getId();
            if ((cacheMenuButton == null && fillMenuButton != null && fillMenuButton.getId() == menuButtonId) ||
                    (cacheMenuButton != null && Objects.equals(menuButtonId, cacheMenuButton.getId())))
                slotList.add(slot);
        }
        return slotList;
    }
}