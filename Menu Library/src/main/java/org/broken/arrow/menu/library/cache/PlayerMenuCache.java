package org.broken.arrow.menu.library.cache;

import org.broken.arrow.menu.library.utility.Function;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

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

		public PlayerMenuData(int currentPage) {
			this.currentPage = currentPage;
		}

		public int getCurrentPage() {
			return currentPage;
		}

		public void setCurrentPage(int currentPage) {
			this.currentPage = currentPage;
		}

		public Function<String> getTitleFunction() {
			return titleFunction;
		}

		public void setTitleFunction(Function<String> titleFunction) {
			this.titleFunction = titleFunction;
		}

		public Function<String> getAnimateTitleFunction() {
			return animateTitleFunction;
		}

		public void setAnimateTitleFunction(Function<String> animateTitleFunction) {
			this.animateTitleFunction = animateTitleFunction;
		}

		public Integer getAnimateTitleTaskId() {
			return animateTitleTaskId;
		}

		public void setAnimateTitleTaskId(Integer animateTitleTaskId) {
			this.animateTitleTaskId = animateTitleTaskId;
		}

		public int getAnimateTitleTime() {
			return animateTitleTime;
		}

		public void setAnimateTitleTime(int animateTitleTime) {
			this.animateTitleTime = animateTitleTime;
		}
	}

	/**
	 * Get or create player data for the given player.
	 */
	public PlayerMenuData getPlayerData(Player player) {
		return playerDataCache.computeIfAbsent(player.getUniqueId(), k -> new PlayerMenuData(0));
	}

	public Map<UUID, PlayerMenuData> getPlayerDataCache() {
		return playerDataCache;
	}

	/**
	 * Remove player data from the cache.
	 */
	public void removePlayerData(Player player) {
		PlayerMenuData data = playerDataCache.remove(player.getUniqueId());
		if (data != null && data.getAnimateTitleTaskId() != null) {
			Bukkit.getScheduler().cancelTask(data.getAnimateTitleTaskId());
			System.out.println("DEBUG: Cancelled animation task for player " + player.getName());
		}
	}

	/**
	 * Clear all player data from the cache.
	 */
	public void clearCache() {
		playerDataCache.values().forEach(data -> {
			if (data.getAnimateTitleTaskId() != null) {
				Bukkit.getScheduler().cancelTask(data.getAnimateTitleTaskId());
			}
		});
		playerDataCache.clear();
		System.out.println("DEBUG: Cleared player menu cache");
	}
}