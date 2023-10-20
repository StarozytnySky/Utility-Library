package org.broken.arrow.menu.button.manager.library.utility;

import javax.annotation.Nonnull;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Logging {

	private final Logger log;
	private static final Builder logBuilder = new Builder();

	public Logging(@Nonnull Class<?> clazz) {
		log = Logger.getLogger(clazz.getName());
	}

	/**
	 * will as default only send the message under info tag.
	 * @param msg the message builder.
	 */
	public void log(Supplier<Builder> msg) {
		this.log(Level.INFO, null, msg);
	}

	public void log(Exception exception, Supplier<Builder> msg) {
		this.log(Level.WARNING, exception, msg);
	}

	public void log(Level level, Supplier<Builder> msg) {
		this.log(level, null, msg);
	}

	public void log(Level level, Exception exception, Supplier<Builder> msg) {
		Builder logMessageBuilder = msg.get();
		if (level != null) {
			if (exception != null) log.log(level, logMessageBuilder.setPlaceholders(), exception);

			else log.log(level, logMessageBuilder.setPlaceholders());
		}
		logMessageBuilder.reset();
	}

	public static Builder of(final String msg, final Object... placeholders) {
		return logBuilder.setMessage(msg).setPlaceholders(placeholders);
	}

	public static final class Builder {
		private String message;
		private Object[] placeholders;

		private Builder() {
		}

		private Builder setMessage(final String msg) {
			this.message = msg;
			return this;
		}

		private Builder setPlaceholders(final Object... placeholders) {
			this.placeholders = placeholders;
			return this;
		}

		private String setPlaceholders() {
			if (placeholders == null) {
				return message;
			}

			StringBuilder msg = new StringBuilder(message);
			for (int i = 0; i < placeholders.length; i++) {
				Object placeholder = placeholders[i];
				int startIndex = msg.indexOf("{" + i + "}");
				if (startIndex >= 0) {
					msg.replace(startIndex, startIndex + 3, placeholder != null ? placeholder + "" : "");
				}
			}
			return msg.toString();
		}

		private void reset() {
			message = null;
			placeholders = null;
		}
	}
}
