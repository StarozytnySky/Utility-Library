package org.broken.arrow.title.update.library;

import org.broken.arrow.title.update.library.utility.TitleLogger;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;
import java.util.logging.Level;


public class UpdateTitle {
	private static boolean hasCastEx;
	private static final float serverVersion;
	private static final TitleLogger titleLogger;

	@Nullable
	private static final ContainerUtility containerUtility;

	static {
		titleLogger = new TitleLogger(UpdateTitle.class);
		synchronized (UpdateTitle.class) {
			SetNmsData nmsData = new SetNmsData();
			serverVersion = nmsData.getServerVersion();
			containerUtility = nmsData.getContainerUtility();
		}
	}

	public static void update(final Player player, final String title) {
		if (hasCastEx) {
			titleLogger.sendLOG(Level.WARNING, "There was an error while updating the title. Please contact the developer for assistance.");
			titleLogger.sendLOG(Level.WARNING, "The set NMS values: " + containerUtility);
			return;
		}
		if (player != null && containerUtility != null && serverVersion > 0)
			try {
				containerUtility.updateInventory(player, title);
			} catch (Exception exception) {
				titleLogger.sendLOG(Level.WARNING, "There was an error while updating the title. Please contact the developer for assistance.");
				titleLogger.sendLOG(exception, Level.WARNING, "");
				hasCastEx = true;
			}
	}


	public static float getServerVersion() {
		return serverVersion;
	}

}

