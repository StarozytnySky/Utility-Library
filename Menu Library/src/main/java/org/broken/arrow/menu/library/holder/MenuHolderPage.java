package org.broken.arrow.menu.library.holder;

import org.broken.arrow.logging.library.Logging;
import org.broken.arrow.menu.library.builders.ButtonData;
import org.broken.arrow.menu.library.builders.MenuDataUtility;
import org.broken.arrow.menu.library.button.MenuButton;
import org.broken.arrow.menu.library.button.MenuButtonPage;
import org.broken.arrow.menu.library.button.logic.ButtonUpdateAction;
import org.broken.arrow.menu.library.button.logic.FillMenuButton;
import org.broken.arrow.menu.library.button.logic.OnRetrieveItem;
import org.broken.arrow.menu.library.utility.FillItems;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Represents a utility class for setting up one or several pages with special objects tied to specific buttons
 * in a paged menu system. It also supports adding custom buttons in non-fill slots, which will be automatically
 * replicated across all pages.
 * <p>&nbsp;</p>
 * <p>
 * If the number of pages is not explicitly overridden, this class will automatically add the necessary number
 * of pages based on the number of objects added, using the specified items as placeholders.
 * </p>
 *
 *
 * <h2>Usage Example</h2>
 * <pre>
 * {@code
 * public class PagedMenu extends MenuHolderPage<Player> {
 *
 *     private final MenuButton forward;
 *     private final MenuButton previous;
 *
 *     public PagedMenu(Player player) {
 *         super(Mainclass.getInstance().getMenuAPI(), new ArrayList<>(Bukkit.getOnlinePlayers()));
 *         setMenuSize(45);
 *         setTitle(() -> "Players in party - Page: " + (getPageNumber() + 1) + " / " + getRequiredPages());
 *         // Which slots you want to fill with items.
 *         setFillSpace("0-35");
 *         // Add this if you want players to add or remove items from slots you set with setFillSpace().
 *         // setSlotsYouCanAddItems();
 *
 *         previous = new MenuButton() {
 *             \u0000@Override
 *             public void onClickInsideMenu(Player player, Inventory menu, ClickType click, ItemStack clickedItem) {
 *                 if (click.isLeftClick()) {
 *                     previousPage();
 *                 }
 *             }
 *
 *             \u0000@Override
 *             public ItemStack getItem() {
 *                 return null;
 *             }
 *         };
 *
 *         forward = new MenuButton() {
 *             \u0000@Override
 *             public void onClickInsideMenu(Player player, Inventory menu, ClickType click, ItemStack clickedItem) {
 *                 if (click.isLeftClick()) {
 *                     nextPage();
 *                 }
 *             }
 *
 *             \u0000@Override
 *             public ItemStack getItem() {
 *                 return null;
 *             }
 *         };
 *     }
 *
 *     \u0000@Override
 *     public FillMenuButton<Player> createFillMenuButton() {
 *         return new FillMenuButton<>((player, itemStacks, clickType, itemStack, playerFromList) -> {
 *             if (playerFromList != null) {
 *                 System.out.println("Clicked on player " + playerFromList.getName());
 *             }
 *             return ButtonUpdateAction.THIS;
 *         }, (slot, player) -> {
 *             return null;
 *         });
 *     }
 *
 *     \u0000@Override
 *     public MenuButton getButtonAt(int slot) {
 *         if (slot == 38) {
 *             return forward;
 *         }
 *         if (slot == 35) {
 *             return previous;
 *         }
 *         return null;
 *     }
 * }
 * }
 * </pre>
 *
 * @param <T> the type of objects added to the list.
 */
public abstract class MenuHolderPage<T> extends HolderUtility<T> {
    private final Logging logger = new Logging(MenuHolderPage.class);
    private final Map<Integer, Integer> fillSlotsMapping = new HashMap<>();
    private FillItems<T> listOfFillItems;

    /**
     * Constructs a paged menu instance specified list of objects. You need to
     * set {@link #setFillSpace(List)} or {@link #setFillSpace(String)}; otherwise, it will automatically use
     * all slots beside the last 9 slots in your menu.
     *
     * @param fillItems The list of items to be displayed inside the GUI on one or several pages.
     */
    protected MenuHolderPage(final List<T> fillItems) {
        this(null, fillItems, false);
    }

    /**
     * Constructs a paged menu instance with specified fill slots and list of objects.
     *
     * @param fillSlots The slots to be filled with items on each page. Must not exceed the inventory size.
     * @param fillItems The list of items to be displayed inside the GUI on one or several pages.
     */
    protected MenuHolderPage(final List<Integer> fillSlots, final List<T> fillItems) {
        this(fillSlots, fillItems, false);
    }

    /**
     * Constructs a paged menu instance with specified fill slots, items, and caching option.
     *
     * @param fillSlots       The slots to be filled with items on each page.
     * @param fillItems       The list of items to be displayed inside the GUI.
     * @param shallCacheItems Set this to false if items and slots should be cached in this class;
     *                        otherwise, override {@link #retrieveMenuButtons(int, MenuDataUtility)} to cache
     *                        them in your own implementation.
     */
    protected MenuHolderPage(@Nullable List<Integer> fillSlots, @Nullable List<T> fillItems, boolean shallCacheItems) {
        super(fillSlots, shallCacheItems);
        if (fillItems != null) {
            this.listOfFillItems = new FillItems<>();
            this.listOfFillItems.setFillItems(fillItems);
        }
        this.amountOfPages();
    }

    /**
     * Provide your logic for incorporating your objects into a specific click and
     * itemstack with this method. Where you get provided with both your object and
     * click action, the player and much more.
     *
     * @return A FillMenuButton instance defining the action to take when a player clicks on the item,
     * the type of item to display for the player, and the item to use when updating the button
     * depending on the object you provide.
     */
    public abstract FillMenuButton<T> createFillMenuButton();

    /**
     * Registers buttons using the list of slots from {@link #getFillSpace()}.
     * You do not need to override this method as you can simply use {@link #createFillMenuButton()},
     * which handles the functionality automatically.
     * <p>&nbsp;</p>
     * This method returns a number from 0 to the highest slot number specified
     * in the list or the inventory's maximum size, whichever is smaller.
     *
     * @param slot the slot number to register the button in.
     * @return The {@link MenuButton} set in the specified slot. Specifically, in this case,
     * it returns an instance of {@link MenuButtonPage}.
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

    @Override
    public void onClick(@Nonnull MenuButton menuButton, @Nonnull Player player, int clickedPos, @Nonnull ClickType clickType, @Nonnull ItemStack clickedItem) {
        int slot = fillSlotsMapping.getOrDefault(clickedPos, -1);
        if (this.getMenu() != null) {
            if (menuButton instanceof MenuButtonPage) {
                final T object = this.getFillItem(slot);
                ((MenuButtonPage<T>) menuButton).onClickInsideMenu(player, this.getMenu(), clickType, clickedItem, object);
            } else {
                menuButton.onClickInsideMenu(player, this.getMenu(), clickType, clickedItem);
            }
        }
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

    protected final void amountOfPages() {
        this.getMenuRenderer().setAmountOfPages(() -> {
            int setPages = getManuallySetPages();
            final List<T> fillItems = this.getListOfFillItems();

            int perPageItems = this.getItemsPerPage();
            int size = this.getInventorySize();
            int itemCount = (fillItems == null || fillItems.isEmpty()) ? (size - 9) : fillItems.size();

            if (perPageItems > size) {
                this.logger.log(Level.WARNING, () ->
                        "Items per page are greater than inventory size. Items per page: " + perPageItems + ". Inventory size: " + size);

                return (double) itemCount / fallbackPerPage(size);
            } else if (perPageItems <= 0) {
                this.logger.log(Level.WARNING, () -> "Items per page must be greater than 0.");
                return 0.0;
            }

            final double pagesAmount = (double) itemCount / perPageItems;

            if (setPages > 0) {
                return Math.max(setPages, pagesAmount);
            }

            return pagesAmount;
        });
    }

    @Override
    public void setButton(final int pageNumber, final MenuDataUtility<T> menuDataUtility, final int slot, final int fillSlotIndex, final boolean isLastFillSlot) {
        final int fillSlot = isLastFillSlot ? -1 : fillSlotIndex;

        boolean isFillSlot = isFillSlot(slot);
        final MenuButton menuButton = getMenuButtonAtSlot(slot, fillSlot, isFillSlot);
        final ItemStack result = getItemAtSlot(menuButton, slot, fillSlot, isFillSlot);

        if (pageNumber == getPageNumber() && fillSlot >= 0) {
            this.fillSlotsMapping.put(slot, fillSlot);
        }

        if (menuButton != null) {
            T fillItem = getFillItem(fillSlot);
            boolean shallAddMenuButton = !isLastFillSlot && isFillSlot && this.getListOfFillItems() != null && !this.getListOfFillItems().isEmpty();
            if (menuButton.shouldUpdateButtons()) this.getButtonsToUpdate().add(menuButton);

            menuDataUtility.putButton(slot, menuButton, buttonDataWrapper -> buttonDataWrapper
                    .setItemStack(result)
                    .setFillButton(shallAddMenuButton)
                    .setObject(fillItem));
        }
    }

    @Override
    protected ItemStack getItemAtSlot(final MenuButton menuButton, final int slot, final int fillSlot, final boolean isFillSlot) {
        if (menuButton == null) return null;

        ItemStack result = null;
        if (isFillSlot) {
            MenuButtonPage<T> menuButtonPage = getPagedMenuButton(menuButton);
            T fillItem = getFillItem(fillSlot);

            if (menuButtonPage != null) {
                if (fillItem != null) result = menuButtonPage.getItem(fillItem);
                if (result == null) result = menuButtonPage.getItem(fillSlot, fillItem);
            }
        }
        if (result == null) result = menuButton.getItem();
        if (result == null) result = menuButton.getItem(fillSlot);

        return result;
    }

    @Override
    @Nullable
    public ItemStack getMenuItem(final MenuButton menuButton, final ButtonData<T> cachedButtonData, final int slot, final boolean updateButton) {
        if (menuButton == null) return null;

        if (updateButton) {
            ItemStack itemStack = menuButton.getItem();
            if (itemStack != null) return itemStack;
            MenuButtonPage<T> menuButtonPage = getPagedMenuButton(menuButton);
            if (menuButtonPage != null) {
                itemStack = menuButtonPage.getItem(cachedButtonData.getObject());
                if (itemStack != null) return itemStack;
                itemStack = menuButtonPage.getItem(slot + (this.getPageNumber() * this.getNumberOfFillItems()), cachedButtonData.getObject());
            }
            return itemStack;
        }
        return null;
    }

    @Nullable
    private MenuButtonPage<T> getPagedMenuButton(MenuButton menuButton) {
        if (menuButton instanceof MenuButtonPage)
            return (MenuButtonPage<T>) menuButton;
        return null;
    }

    private int fallbackPerPage(int size) {
        int adjusted = size - 9;
        return adjusted <= 1 ? 9 : adjusted;
    }

    private boolean isFillSlot(final int slot) {
        final List<Integer> fillSlots = this.getFillSpace();
        return !fillSlots.isEmpty() && fillSlots.contains(slot);
    }
}
