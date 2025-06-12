package org.broken.arrow.menu.library.holder.utility;

import org.broken.arrow.menu.library.MenuUtility;
import org.broken.arrow.menu.library.utility.Function;
import org.broken.arrow.menu.library.utility.ServerVersion;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import javax.annotation.Nullable;

public class AnimateTitleTask<T> extends BukkitRunnable {

    private final MenuUtility<T> menuUtility;
    @Nullable
    private final Player player; // Nullable for global animation
    private int taskId;
    private volatile boolean cancelled = false;


    // Globalna animacja
    public AnimateTitleTask(MenuUtility<T> menuUtility) {
        this(menuUtility, null);
    }

    // Animacja per gracz
    public AnimateTitleTask(MenuUtility<T> menuUtility, @Nullable Player player) {
        this.menuUtility = menuUtility;
        this.player = player;
    }


    public void runTask(long delay) {
        taskId = runTaskTimerAsynchronously(menuUtility.getPlugin(), 1L, delay).getTaskId();
        System.out.println("DEBUG: Started AnimateTitleTask " + (player != null ? "for player " + player.getName() : "globally") + " with delay " + delay);
    }

    public boolean isRunning() {
         return taskId > 0 &&
                (Bukkit.getScheduler().isCurrentlyRunning(taskId) ||
                        Bukkit.getScheduler().isQueued(taskId));
    }

    public void stopTask() {
        if (this.isRunning()) {
            this.cancelled = true;
            Bukkit.getScheduler().cancelTask(this.taskId);
        }
    }

    @Override
    public void run() {
        if (cancelled) return;

        Function<?> animateTitle;

        if (player != null) {
                if (!player.isOnline()) {
                    cancelled = true;
                    cancel();
                    menuUtility.updateTitle(player);
                    System.out.println("DEBUG: AnimateTitleTask cancelled for player " + player.getName() + ": Player offline or menu closed");
                    return;
                }

            animateTitle = menuUtility.getPlayerMenuCache().getPlayerData(player).getAnimateTitleFunction();
        } else {
            animateTitle = menuUtility.getAnimateTitle();
        }

        Object text = animateTitle.apply();
        if (!player.getOpenInventory().getTopInventory().equals(menuUtility.getPlayerMenuCache().getPlayerData(player).getInventory()) || text == null || (ServerVersion.atLeast(ServerVersion.V1_9) && isCancelled())) {
            cancelled = true;
            cancel();
            if (player != null) {
                menuUtility.updateTitle(player);
            } else
                menuUtility.updateTitle();
            System.out.println("DEBUG: Animate Title task has been cancelled. Text is null.");
            return;
        }
        if (!text.equals("")) {
            //System.out.println("DEBUG: Update title "  + text);
            if (player != null) {
                menuUtility.updateTitle(player, text);
            } else
                menuUtility.updateTitle(text);
        } else {
            cancelled = true;
            cancel();
        }
    }
}
