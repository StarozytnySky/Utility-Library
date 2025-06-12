package org.broken.arrow.menu.library.cache;

import org.broken.arrow.menu.library.utility.Function;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Cache for player-specific menu data, such as current page and title settings.
 */
public class PlayerMenuCache {

	private final Map<UUID, PlayerMenuData> playerDataCache = new HashMap<>();

	/**
	 * Data class to store player-specific menu information.
	 */
	public static class PlayerMenuData {
		private int currentPage;
		private Function<String> titleFunction;
		private Function<String> animateTitleFunction;
		private Integer animateTitleTaskId;
		private int animateTitleTime;
		private Inventory inventory;

		/**
		 * Constructs a new PlayerMenuData instance with the specified current page.
		 *
		 * @param currentPage the current page of the player's menu
		 */
		public PlayerMenuData(int currentPage) {
			this.currentPage = currentPage;
		}

		/**
		 * Retrieves the current page of the player's menu.
		 *
		 * @return the current page number.
		 */
		public int getCurrentPage() {
			return currentPage;
		}

		/**
		 * Sets the current page of the player's menu.
		 *
		 * @param currentPage the current page number to be set.
		 */
		public void setCurrentPage(int currentPage) {
			this.currentPage = currentPage;
		}

		/**
		 * Retrieves the function responsible for generating a title.
		 *
		 * @return a {@code Function<String>} representing the title generator function.
		 */
		public Function<String> getTitleFunction() {
			return titleFunction;
		}

		/**
		 * Sets the function that generates the title for the player's menu.
		 *
		 * @param titleFunction the function responsible for generating a title,
		 *                      represented as a {@code Function<String>}.
		 */
		public void setTitleFunction(Function<String> titleFunction) {
			this.titleFunction = titleFunction;
		}

		/**
		 * Retrieves the function responsible for animating the title of the player's menu.
		 *
		 * @return a {@code Function<String>} representing the title animation function.
		 */
		public Function<String> getAnimateTitleFunction() {
			return animateTitleFunction;
		}

		/**
		 * Sets the function responsible for animating the title of the player's menu.
		 *
		 * @param animateTitleFunction the function to define how the title should be animated,
		 *                             represented as a {@code Function<String>}.
		 */
		public void setAnimateTitleFunction(Function<String> animateTitleFunction) {
			this.animateTitleFunction = animateTitleFunction;
		}

		/**
		 * Retrieves the task ID for the animation of the menu title.
		 *
		 * @return the task ID associated with the menu title animation, or null if not set.
		 */
		public Integer getAnimateTitleTaskId() {
			return animateTitleTaskId;
		}

		/**
		 * Sets the task ID associated with the animation of the menu title.
		 *
		 * @param animateTitleTaskId the task ID to be set for menu title animation; can be null to unset the current ID.
		 */
		public void setAnimateTitleTaskId(Integer animateTitleTaskId) {
			this.animateTitleTaskId = animateTitleTaskId;
		}

		/**
		 * Retrieves the duration of the title animation for the player's menu.
		 *
		 * @return the animation duration time in milliseconds.
		 */
		public int getAnimateTitleTime() {
			return animateTitleTime;
		}

		/**
		 * Sets the duration of the title animation for the player's menu.
		 *
		 * @param animateTitleTime the duration of the animation, specified in milliseconds
		 */
		public void setAnimateTitleTime(int animateTitleTime) {
			this.animateTitleTime = animateTitleTime;
		}

		/**
		 * Retrieves the inventory associated with the player's menu data.
		 *
		 * @return the {@code Inventory} instance tied to the player's menu.
		 */
		public Inventory getInventory() {
			return this.inventory;
		}

		/**
		 * Sets the inventory associated with the player's menu data.
		 *
		 * @param inventory the {@code Inventory} instance to be set for the player's menu data.
		 */
		public void setInventory(Inventory inventory) {
			this.inventory  = inventory;
		}
	}

	/**
	 * Retrieves or creates a player's menu data. If the player data is not already cached,
	 * it will be initialized with default values and stored in the cache.
	 *
	 * @param player the player whose menu data should be retrieved or initialized
	 * @return the {@link PlayerMenuData} object associated with the specified player
	 */
	public PlayerMenuData getPlayerData(Player player) {
		return playerDataCache.computeIfAbsent(player.getUniqueId(), k -> new PlayerMenuData(0));
	}

	/**
	 * Checks if the specified player's data is cached.
	 *
	 * @param player the player whose data should be checked in the cache
	 * @return true if the player's data is present in the cache, false otherwise
	 */
	public boolean isCached(Player player) {
		return playerDataCache.containsKey(player.getUniqueId());
	}

	/**
	 * Retrieves the current cache of player-specific menu data.
	 *
	 * @return a map containing player-specific menu data, where the keys are the UUIDs of players
	 *         and the values are {@link PlayerMenuData} objects representing their cached menu data.
	 */
	public Map<UUID, PlayerMenuData> getPlayerDataCache() {
		return playerDataCache;
	}

	/**
	 * Removes the player's menu data from the cache and cancels any ongoing title animation
	 * tasks associated with the player, if applicable.
	 *
	 * @param player the player whose menu data should be removed from the cache
	 */
	public void removePlayerData(Player player) {
		PlayerMenuData data = playerDataCache.remove(player.getUniqueId());
		if (data != null && data.getAnimateTitleTaskId() != null) {
			Bukkit.getScheduler().cancelTask(data.getAnimateTitleTaskId());
		}
	}

	/**
	 * Clears all cached player menu data.
	 *
	 * This method iterates through the cached player data and cancels any
	 * active title animation tasks associated with the players. Afterward,
	 * it clears the entire cache map, removing all stored player menu data.
	 * A debug message is printed to indicate the cache has been cleared.
	 */
	public void clearCache() {
		playerDataCache.values().forEach(data -> {
			if (data.getAnimateTitleTaskId() != null) {
				Bukkit.getScheduler().cancelTask(data.getAnimateTitleTaskId());
			}
		});
		playerDataCache.clear();
	}
}