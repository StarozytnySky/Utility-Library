package org.broken.arrow.menu.library.holder.utility;

import org.broken.arrow.logging.library.Logging;
import org.broken.arrow.menu.library.MenuUtility;
import org.broken.arrow.menu.library.builders.ButtonData;
import org.broken.arrow.menu.library.cache.PlayerMenuCache;
import org.broken.arrow.menu.library.holder.MenuHolderShared;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

public class InventoryRenderer<T> {

    private final MenuUtility<T> utility;
    private final Logging logger = new Logging(MenuUtility.class);

    public InventoryRenderer(MenuUtility<T> utility) {
        this.utility = utility;
    }

    @Nonnull
    public Inventory redraw() {
        return redraw(null,false);
    }

    @Nonnull
    public Inventory redraw(@Nullable Player player, boolean sharedMode) {
        Inventory menu;
        int pageNumber;
        if (player != null && utility instanceof MenuHolderShared) {
            MenuHolderShared<?> sharedMenu = (MenuHolderShared<?>) utility;
            PlayerMenuCache.PlayerMenuData cacheData = sharedMenu.getPlayerMenuCache().getPlayerData(player);
            menu = cacheData.getInventory();
            pageNumber = cacheData.getCurrentPage();
            System.out.println("DEBUG: Redrawing shared menu for player " + player.getName() + ", page: " + pageNumber);
        } else {
            System.out.println("DEBUG: Redrawing default menu");
            menu = utility.getMenu();
            pageNumber = utility.getPageNumber();

        }

        int size = utility.getInventorySize();
        List<Integer> fillSpace = utility.getFillSpace();

        if (menu == null || size > menu.getSize()) {
            System.out.println("DEBUG: Menu is null or size mismatch, creating new inventory");
            menu = createInventory();
        }

        if (sharedMode && utility instanceof MenuHolderShared) {
            Map<Integer, ButtonData<T>> buttons = utility.getMenuButtons(pageNumber);
            for (int slot = 0; slot < menu.getSize(); slot++) {
                if (!fillSpace.contains(slot)) {

                ButtonData<?> data = buttons != null ? buttons.get(utility.getSlot(slot)) : null;
                ItemStack item = data != null ? data.getItemStack() : null;
                menu.setItem(slot, item);
                System.out.println("DEBUG: Shared mode - Set slot " + slot + " to item: " + (item != null ? item.getType() : "null"));
                }
            }
        } else {
            int fillSlots = !fillSpace.isEmpty() ? fillSpace.size() : menu.getSize();

            for (int i = fillSpace.stream().findFirst().orElse(0); i < fillSlots; i++) {
                menu.setItem(i, new ItemStack(Material.AIR));
            }
            Map<Integer, ButtonData<T>> buttons = utility.getMenuButtons(utility.getPageNumber());
            if (buttons != null && !buttons.isEmpty()) {
                for (int i = 0; i < menu.getSize(); i++) {
                    ButtonData<?> data = buttons.get(utility.getSlot(i));
                    ItemStack item = data != null ? data.getItemStack() : null;
                    menu.setItem(i, item);
                    System.out.println("DEBUG: Default mode - Set slot " + i + " to item: " + (item != null ? item.getType() : "null"));
                }
            }
        }
        return menu;
    }

    @Nonnull
    public Inventory createInventory() {
        String title = Optional.ofNullable(utility.getTitle()).map(Object::toString).orElse(" ");
        InventoryType type = utility.getInventoryType();
        int size = utility.getInventorySize();

        if (type != null) return Bukkit.createInventory(null, type, title);

        if (!(size == 5 || size % 9 == 0)) {
            this.logger.log(Level.WARNING, () -> Logging.of("Wrong inventory size, you have set " + size + ". It needs to be a valid number."));
        }

        if (size == 5) return Bukkit.createInventory(null, InventoryType.HOPPER, title);

        return Bukkit.createInventory(null, size % 9 == 0 ? size : 9, title);
    }
}