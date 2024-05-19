package org.broken.arrow.color.library;

import com.google.gson.JsonObject;
import net.md_5.bungee.api.ChatColor;
import org.broken.arrow.color.library.Component.Builder;
import org.broken.arrow.color.library.utility.CreateComponent;
import org.broken.arrow.color.library.utility.CreateFromLegacyText;
import org.broken.arrow.color.library.utility.TextGradientUtil;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TextTranslator {
	private static final Pattern HEX_PATTERN = Pattern.compile("(?<!\\\\\\\\)(<#[a-fA-F0-9]{6}>)|(?<!\\\\\\\\)(<#[a-fA-F0-9]{3}>)");
	private static final Pattern GRADIENT_PATTERN = Pattern.compile("(<#[a-fA-F0-9]{6}:#[a-fA-F0-9]{6}>)");
	private static final Pattern url = Pattern.compile("^(?:(https?)://)?([-\\w_\\.]{2,}\\.[a-z]{2,4})(/\\S*)?$");
	private static final TextTranslator instance = new TextTranslator();

	public static TextTranslator getInstance() {
		return instance;
	}

	/**
	 * Type your message/string text here. you use this format for colors:
	 * <ul>
	 * <li> For vanilla color codes <strong>&amp; or &#167;</strong> and the color code.</li>
	 * <li> For hex <strong>&lt;#5e4fa2&gt;</strong> </li>
	 * <li> For normal gradients <strong>&lt;#5e4fa2:#f79459&gt;</strong> </li>
	 * <li> For hsv use <strong>gradients_hsv_&lt;#5e4fa2:...&gt;</strong> add at least 2 colors or more</li>
	 * <li> For use multicolor <strong>gradients_&lt;#6B023E:...&gt;</strong>add at least 2 colors or more </li>
	 * <li> For change balance between colors add this to the end of gradients or gradients_hsv <strong>_portion&lt;0.2:0.6:0.2&gt;</strong>
	 *  Like this <strong>gradients_&lt;#6B023E:#3360B3:#fc9:#e76424&gt;_portion&lt;0.2:0.6:0.2&gt;</strong> ,
	 *  If you not add this it will have even balance between colors.</li>
	 * </ul>
	 *
	 * @param message your string message.
	 * @return spigot compatible translation.
	 */
	public static String toSpigotFormat(String message) {
		return getInstance().spigotFormat(message);
	}

	/**
	 * This is for component when you want to send message
	 * thru vanilla minecraft MNS for example. DO NOT WORK IN SPIGOT API. Use {@link #toSpigotFormat(String)}
	 * <br> You use this format for colors:<br>
	 * <ul>
	 * <li> For vanilla color codes <strong>&amp; or &#167;</strong> and the color code.</li>
	 * <li> For hex <strong>&lt;#5e4fa2&gt;</strong> </li>
	 * <li> For normal gradients <strong>&lt;#5e4fa2:#f79459&gt;</strong> </li>
	 * <li> For hsv use <strong>gradients_hsv_&lt;#5e4fa2:...&gt;</strong> add at least 2 colors or more</li>
	 * <li> For use multicolor <strong>gradients_&lt;#6B023E:...&gt;</strong>add at least 2 colors or more </li>
	 * <li> For change balance between colors add this to the end of gradients or gradients_hsv <strong>_portion&lt;0.2:0.6:0.2&gt;</strong>
	 *  Like this <strong>gradients_&lt;#6B023E:#3360B3:#fc9:#e76424&gt;_portion&lt;0.2:0.6:0.2&gt;</strong> ,
	 *  If you not add this it will have even balance between colors.</li>
	 * </ul>
	 *
	 * @param message      your string message.
	 * @param defaultColor set default color when colors are not set in the message.
	 * @return json object with the set colors.
	 */
	public static JsonObject toComponent(String message, String defaultColor) {
		return getInstance().componentFormat(message, defaultColor);
	}

	/**
	 * This is for component when you want to send message
	 * thru vanilla minecraft MNS for example. DO NOT WORK IN SPIGOT API. Use {@link #toSpigotFormat(String)}
	 * <br> You use this format for colors:<br>
	 * <ul>
	 * <li> For vanilla color codes <strong>&amp; or &#167;</strong> and the color code.</li>
	 * <li> For hex <strong>&lt;#5e4fa2&gt;</strong> </li>
	 * <li> For normal gradients <strong>&lt;#5e4fa2:#f79459&gt;</strong> </li>
	 * <li> For hsv use <strong>gradients_hsv_&lt;#5e4fa2:...&gt;</strong> add at least 2 colors or more</li>
	 * <li> For use multicolor <strong>gradients_&lt;#6B023E:...&gt;</strong>add at least 2 colors or more </li>
	 * <li> For change balance between colors add this to the end of gradients or gradients_hsv <strong>_portion&lt;0.2:0.6:0.2&gt;</strong>
	 *  Like this <strong>gradients_&lt;#6B023E:#3360B3:#fc9:#e76424&gt;_portion&lt;0.2:0.6:0.2&gt;</strong> ,
	 *  If you not add this it will have even balance between colors.</li>
	 * </ul>
	 *
	 * @param message your string message.
	 * @return json object with the set colors.
	 */

	public static JsonObject toComponent(String message) {
		return getInstance().componentFormat(message, null);
	}

	/**
	 * This is for component when you want to send message
	 * thru vanilla minecraft MNS for example..DO NOT WORK IN SPIGOT API. Use {@link #toSpigotFormat(String)}
	 *
	 * @param message      your string message.
	 * @param defaultColor set default color when colors are not set in the message.
	 * @return json object with the set colors.
	 */
	private JsonObject componentFormat(String message, String defaultColor) {
		CreateComponent createComponent = new CreateComponent(this);
		return createComponent.componentFormat(message,defaultColor);
	}


	/**
	 * Type your message/string text here. you use
	 * <p>
	 * §/& color code or <#55F758> for normal hex and
	 * <#5e4fa2:#f79459> for gradient (use any color code to stop gradient).
	 *
	 * @param message your string message.
	 * @return spigot compatible translation.
	 */

	private String spigotFormat(String message) {
		String messageCopy = checkStringForGradient(message);
		Matcher matcher = HEX_PATTERN.matcher(messageCopy);

		while (matcher.find()) {
			String match = matcher.group(0);
			int firstPos = match.indexOf("#");
			if (match.length() <= 9)
				messageCopy = messageCopy.replace(match, "&x&" + match.charAt(firstPos + 1) + "&" + match.charAt(firstPos + 1) + "&" + match.charAt(firstPos + 2) + "&" + match.charAt(firstPos + 2) + "&" + match.charAt(firstPos + 3) + "&" + match.charAt(firstPos + 3));
			else
				messageCopy = messageCopy.replace(match, "&x&" + match.charAt(firstPos + 1) + "&" + match.charAt(firstPos + 2) + "&" + match.charAt(firstPos + 3) + "&" + match.charAt(firstPos + 4) + "&" + match.charAt(firstPos + 5) + "&" + match.charAt(firstPos + 6));
		}
		return ChatColor.translateAlternateColorCodes('&', messageCopy);
	}

	/**
	 * Check the string if it contains gradients.
	 * <ul>
	 * <li> For vanilla color codes <strong>&amp; or §</strong> and the color code.</li>
	 * <li> For hex <strong>&lt;#5e4fa2&gt;</strong> </li>
	 * <li> For normal gradients <strong>&lt;#5e4fa2:#f79459&gt;</strong> </li>
	 * <li> For hsv use <strong>gradients_hsv_&lt;#5e4fa2:...&gt;</strong> add at least 2 colors or more</li>
	 * <li> For use multicolor <strong>gradients_&lt;#6B023E:...&gt;</strong>add at least 2 colors or more </li>
	 * <li> For change balance between colors add this to the end of gradients or gradients_hsv <strong>_portion&lt;0.2:0.6:0.2&gt;</strong>
	 *  Like this <strong>gradients_&lt;#6B023E:#3360B3:#fc9:#e76424&gt;_portion&lt;0.2:0.6:0.2&gt;</strong> ,
	 *  If you not add this it will have even balance between colors.</li>
	 * </ul>
	 *
	 * @param text to check.
	 * @return the message translated if has any gradients or untouched.
	 */
	public String checkStringForGradient(final String text) {
		String messageCopy = text;
		GradientType type = null;
		TextGradientUtil textGradientUtil;
		if (text.contains(GradientType.HSV_GRADIENT_PATTERN.getType()))
			type = GradientType.HSV_GRADIENT_PATTERN;
		if (text.contains(GradientType.SIMPLE_GRADIENT_PATTERN.getType()))
			type = GradientType.SIMPLE_GRADIENT_PATTERN;
		if (type != null) {
			textGradientUtil = new TextGradientUtil(type, text);
			StringBuilder builder = new StringBuilder();
			for (String textSplit : textGradientUtil.splitOnGradient())
				builder.append(textGradientUtil.convertToMultiGradients(textSplit));
			messageCopy = builder.toString();
		}
		if (messageCopy == null)
			messageCopy = text;
		Matcher matcherGradient = GRADIENT_PATTERN.matcher(messageCopy);
		if (matcherGradient.find()) {
			textGradientUtil = new TextGradientUtil(type, messageCopy);
			messageCopy = textGradientUtil.convertGradients();
		}
		return messageCopy;
	}

	/**
	 * Converts a legacy Spigot formatted string to a JSON object, suitable for use with Minecraft's chat serializer.
	 * Most usefully for Minecraft version 1.16 and newer, when you want to use gradients or hexadecimal colors,
	 * and not want to use my methods to convert colors.
	 * <p>&nbsp;</p>
	 * <p>
	 * Legacy Spigot formatting uses symbols like '&amp;' or '§' and supports two specific formats:
	 * </p>
	 * <ul>
	 *   <li>Hexadecimal format: e.g., '&amp;x&amp;d&amp;4&amp;c&amp;3&amp;1&amp;1'</li>
	 *   <li>Color codes: e.g., '&amp;f' for white.</li>
	 * </ul>
	 *
	 * @param message      The input string to check and convert to JSON.
	 * @param defaultColor The default color to use if a color is not specified in the message.
	 * @return A JSON object representing the formatted text.
	 */
	public static JsonObject fromLegacyText(String message, ChatColors defaultColor) {
		return CreateFromLegacyText.fromLegacyText( message,defaultColor);
	}

	public void setColor(final String defaultColor, final Builder component, String format) {
		if (format.equals(ChatColors.BOLD.getName())) {
			component.bold(true);
		} else if (format.equals(ChatColors.ITALIC.getName())) {
			component.italic(true);
		} else if (format.equals(ChatColors.UNDERLINE.getName())) {
			component.underline(true);
		} else if (format.equals(ChatColors.STRIKETHROUGH.getName())) {
			component.strikethrough(true);
		} else if (format.equals(ChatColors.MAGIC.getName())) {
			component.obfuscated(true);
		} else if (format.equals(ChatColors.RESET.getName())) {
			format = defaultColor;
			component.reset(true);
			component.colorCode(format);
		} else {
			component.colorCode(format);
		}
	}

	public enum GradientType {
		SIMPLE_GRADIENT_PATTERN("gradients_"),
		HSV_GRADIENT_PATTERN("gradients_hsv_");
		private final String type;

		GradientType(String type) {
			this.type = type;
		}

		public String getType() {
			return type;
		}
	}
}
