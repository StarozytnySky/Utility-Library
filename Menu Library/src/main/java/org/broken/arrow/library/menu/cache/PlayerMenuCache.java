package org.broken.arrow.library.menu.cache;

import org.broken.arrow.library.menu.runnable.AnimateTitleTask;
import org.broken.arrow.library.menu.runnable.ButtonAnimation;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Cache for player-specific menu data, such as current page and title settings.
 */
public class PlayerMenuCache<T> {
	private final Map<UUID, PlayerMenuData<T>> playerDataCache = new HashMap<>();

	/**
	 * Get or create player data for the given player.
	 * @param player the player whose data is to be retrieved
	 * @return the playerdata
	 */
	public PlayerMenuData<T> getPlayerData(Player player) {
		return playerDataCache.computeIfAbsent(player.getUniqueId(), k -> new PlayerMenuData<>(0));
	}

	/**
	 * Get the entire player data cache.
	 * @return the player data cache
	 */
	public Map<UUID, PlayerMenuData<T>> getPlayerDataCache() {
		return playerDataCache;
	}

	/**
	 * Remove player data from the cache.
	 * @param player the player whose data should be removed
	 */
	public void removePlayerData(Player player) {
		PlayerMenuData<T> data = playerDataCache.remove(player.getUniqueId());
		if (data != null) {
			// Cancel title animation
			final AnimateTitleTask<T> animateTitleTask = data.getAnimateTitleTask();
			if (animateTitleTask != null) {
				animateTitleTask.stopTask();
			}

			// Cancel button animation
			final ButtonAnimation<T> buttonAnimation = data.getButtonAnimation();
			if (buttonAnimation != null) {
				buttonAnimation.stopTask();
			}

			System.out.println("DEBUG: Cancelled all animation tasks for player " + player.getName());
		}

	}

	/**
	 * Clear all player data from the cache.
	 */
	public void clearCache() {
		playerDataCache.entrySet().forEach(data -> {
			final AnimateTitleTask<T> animateTitleTask = data != null && data.getValue() != null ? data.getValue().getAnimateTitleTask() : null;
			if (data != null && animateTitleTask != null) {
				animateTitleTask.stopTask();
			}
		});

		playerDataCache.clear();
		System.out.println("DEBUG: Cleared player menu cache");
	}

	/**
	 * Data class to store player-specific menu information.
	 */
	public static class PlayerMenuData<T> {
		private int currentPage;
		private Supplier<String> titleFunction;
		private Supplier<String> animateTitleFunction;
		private AnimateTitleTask<T> animateTitleTask;
		private ButtonAnimation<T> buttonAnimation;

		/**
		 * Constructor to initialize player menu data with the current page.
		 *
		 * @param currentPage the current page number
		 */
		public PlayerMenuData(int currentPage) {
			this.currentPage = currentPage;
		}

		/**
		 * Get the title animation task.
		 *
		 * @return the title animation task
		 */
		public AnimateTitleTask<T> getAnimateTitleTask() {
			return animateTitleTask;
		}

		/**
		 * Set the title animation task.
		 *
		 * @param animateTitleTask the title animation task to set
		 */
		public void setAnimateTitleTask(final AnimateTitleTask<T> animateTitleTask) {
			this.animateTitleTask = animateTitleTask;
		}


		/**
		 * Get the current page number.
		 *
		 * @return the current page number
		 */
		public int getCurrentPage() {
			return currentPage;
		}

		/**
		 * Set the current page number.
		 *
		 * @param currentPage the current page number to set
		 */
		public void setCurrentPage(int currentPage) {
			this.currentPage = currentPage;
		}

		/**
		 * Get the function that provides titles.
		 *
		 * @return the function providing titles
		 */
		public Supplier<String> getTitleFunction() {
			return titleFunction;
		}

		/**
		 * Set the function that provides titles.
		 *
		 * @param titleFunction the function to set
		 */
		public void setTitleFunction(Supplier<String> titleFunction) {
			this.titleFunction = titleFunction;
		}

		/**
		 * Get the function that provides animated titles.
		 *
		 * @return the function providing animated titles
		 */
		public Supplier<String> getAnimateTitleFunction() {
			return animateTitleFunction;
		}

		/**
		 * Set the function that provides animated titles.
		 *
		 * @param animateTitleFunction the function to set
		 */
		public void setAnimateTitleFunction(Supplier<String> animateTitleFunction) {
			this.animateTitleFunction = animateTitleFunction;
		}

		/**
		 * Get the button animation task.
		 *
		 * @return the button animation task
		 */
		public ButtonAnimation<T> getButtonAnimation() {
			return buttonAnimation;
		}

		/**
		 * Set the button animation task.
		 *
		 * @param buttonAnimation the button animation task to set
		 */
		public void setButtonAnimation(final ButtonAnimation<T> buttonAnimation) {
			this.buttonAnimation = buttonAnimation;
		}
	}
}