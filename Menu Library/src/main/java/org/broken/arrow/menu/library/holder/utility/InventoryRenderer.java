package org.broken.arrow.menu.library.holder.utility;

import org.broken.arrow.logging.library.Logging;
import org.broken.arrow.menu.library.MenuUtility;
import org.broken.arrow.menu.library.builders.ButtonData;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nonnull;
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
        return redraw(false);
    }

    @Nonnull
    public Inventory redraw(boolean sharedMode) {
        Inventory menu = utility.getMenu();
        int size = utility.getInventorySize();
        List<Integer> fillSpace = utility.getFillSpace();

        if (menu == null || size > menu.getSize()) {
            menu = createInventory();
        }

        if (sharedMode) {
            // Aktualizuj tylko sloty z getButtonAt, zachowaj fillSlots
            Map<Integer, ButtonData<T>> buttons = utility.getMenuButtons(utility.getPageNumber());
            for (int slot = 0; slot < menu.getSize(); slot++) {
                if (!fillSpace.contains(slot)) {
                    ButtonData<?> data = buttons != null ? buttons.get(slot) : null;
                    menu.setItem(slot, data != null ? data.getItemStack() : null);
                }
            }
        } else {
            // Standardowe renderowanie
            int fillSlots = !fillSpace.isEmpty() ? fillSpace.size() : menu.getSize();
            for (int i = fillSpace.stream().findFirst().orElse(0); i < fillSlots; i++) {
                menu.setItem(i, new ItemStack(Material.AIR));
            }
            Map<Integer, ButtonData<T>> buttons = utility.getMenuButtons(utility.getPageNumber());
            if (buttons != null && !buttons.isEmpty()) {
                for (int i = 0; i < menu.getSize(); i++) {
                    ButtonData<?> data = buttons.get(utility.getPageNumber() * size + i);
                    menu.setItem(i, data != null ? data.getItemStack() : null);
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
            this.logger.log(Level.WARNING, () -> Logging.of("Wrong inventory size , you has put in " + size + " it need to be valid number."));
        }

        if (size == 5) return Bukkit.createInventory(null, InventoryType.HOPPER, title);

        return Bukkit.createInventory(null, size % 9 == 0 ? size : 9, title);
    }
}