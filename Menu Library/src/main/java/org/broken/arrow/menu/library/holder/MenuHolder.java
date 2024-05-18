package org.broken.arrow.menu.library.holder;

import java.util.List;
import java.util.Map;

/**
 * This class handles single-page menus or paged menus if you implement the logic yourself.
 * <p>&nbsp;</p>
 * If you wish to fill the menu using methods other than slot numbers,
 * such as filling multiple pages, consider using {@link MenuHolderPage} for a better alternative.
 * This allows you to interact with your objects directly without creating your own logic.
 * <p>&nbsp;</p>
 * Alternatively, you can achieve similar results with this class by setting fill slots using
 * {@link #setFillSpace(String)} or {@link #setFillSpace(List)} and manually setting the number
 * of pages using {@link #setManuallyAmountOfPages(int)}. Otherwise, only one page will be used.
 *
 * <h2>Usage Example</h2>
 * <pre>
 * {@code
 * public class MyMenu extends MenuHolder {
 *
 *     private final MenuButton exampleButton;
 *
 *     public MyMenu() {
 *         setMenuSize(45);
 *         setTitle("Menu Title");
 *
 *         exampleButton = new MenuButton() {
 *             \u0000@Override
 *             public void onClickInsideMenu(Player player, Inventory menu, ClickType click, ItemStack clickedItem) {
 *                 // Actions to execute when clicking on the item.
 *             }
 *
 *             \u0000@Override
 *             public ItemStack getItem() {
 *                 // Item to be returned by this button.
 *                 return null;
 *             }
 *         };
 *     }
 *
 *     \u0000@Override
 *     public MenuButton getButtonAt(int slot) {
 *         if (slot == 1) {
 *             return exampleButton;
 *         }
 *         return null;
 *     }
 * }
 * }
 * </pre>
 */
public class MenuHolder extends HolderUtility<Object> {

    /**
     * Constructs a menu instance without any arguments. It is recommended to set the menu size using {@link #setMenuSize(int)},
     * as the default size is set to zero.
     */
    protected MenuHolder() {
        this(null, false);
    }

    /**
     * Constructs a menu instance with specified fill slots. It is recommended to set the menu size using {@link #setMenuSize(int)},
     * as the default size is set to zero.
     *
     * @param fillSlots The slots you want to fill with items, and you need to set the amount of pages if your plan
     *                  to use mor than one page.
     */
    protected MenuHolder(final List<Integer> fillSlots) {
        this(fillSlots, false);
    }

    /**
     * Constructs a menu instance with specified caching option.
     *
     * @param shallCacheItems Set this to false if items and slots should be cached in this class.
     *                        Otherwise, override {@link #retrieveMenuButtons(int, Map)} to cache
     *                        them in your own implementation.
     */
    protected MenuHolder(final boolean shallCacheItems) {
        this(null, shallCacheItems);
    }

    /**
     * Constructs a menu instance with specified fill slots and caching option.
     * It is recommended to set the menu size using {@link #setMenuSize(int)},
     * as the default size is set to zero.
     *
     * @param fillSlots       The slots you want to fill with items, and you need to set the amount of pages if your plan
     *                        to use mor than one page.
     * @param shallCacheItems Set this to false if items and slots should be cached in this class.
     *                        Otherwise, override {@link #retrieveMenuButtons(int, Map)} to cache
     *                        them in your own implementation.
     */
    protected MenuHolder(final List<Integer> fillSlots, boolean shallCacheItems) {
        super(fillSlots, shallCacheItems);
    }

    /**
     * Create menu instance.
     *
     * @param fillSlots Witch slots you want fill with items.
     * @param fillItems List of items you want parse inside gui on one or several pages.
     * @deprecated the list of fillSlots and fillItems will be removed, use {@link MenuHolderPage} for set up paged menus.
     */
    @Deprecated
    protected MenuHolder(final List<Integer> fillSlots, final List<?> fillItems) {
        this(fillSlots, null, false);
    }

    /**
     * Create menu instance.
     *
     * @param fillSlots       Witch slots you want fill with items.
     * @param fillItems       List of items you want parse inside gui.
     * @param shallCacheItems set to true if you want to cache items and slots.
     * @deprecated the list of fillSlots and fillItems will be removed, use {@link MenuHolderPage} for set up paged menus.
     */
    @Deprecated
    protected MenuHolder(final List<Integer> fillSlots, final List<?> fillItems, final boolean shallCacheItems) {
        super(fillSlots, shallCacheItems);
    }

}
