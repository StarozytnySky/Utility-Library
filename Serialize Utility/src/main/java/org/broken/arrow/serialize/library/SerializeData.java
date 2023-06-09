package org.broken.arrow.serialize.library;

import com.google.gson.Gson;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.chat.ComponentSerializer;
import org.broken.arrow.serialize.library.utility.converters.SerializeingLocation;
import org.broken.arrow.serialize.library.utility.serialize.ConfigurationSerializable;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.MemorySection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


public final class SerializeData {
	private static final float serverVersion;

	public SerializeData(final float serverVersion) {

	}

	static {
		final String[] versionPieces = Bukkit.getServer().getBukkitVersion().split("\\.");
		final String firstNumber;
		String secondNumber;
		final String firstString = versionPieces[1];
		if (firstString.contains("-")) {
			firstNumber = firstString.substring(0, firstString.lastIndexOf("-"));

			secondNumber = firstString.substring(firstString.lastIndexOf("-") + 1);
			final int index = secondNumber.toUpperCase().indexOf("R");
			if (index >= 0)
				secondNumber = secondNumber.substring(index + 1);
		} else {
			final String secondString = versionPieces[2];
			firstNumber = firstString;
			secondNumber = secondString.substring(0, secondString.lastIndexOf("-"));
		}
		serverVersion = Float.parseFloat(firstNumber + "." + secondNumber);
	}

	public static Object serialize(final Object obj) {
		if (obj == null)
			return null;

		else if (obj instanceof ChatColor)
			return ((ChatColor) obj).name();

		else if (obj instanceof net.md_5.bungee.api.ChatColor) {
			final net.md_5.bungee.api.ChatColor color = ((net.md_5.bungee.api.ChatColor) obj);

			return serverVersion >= 16.0 ? color.toString() : color.name();
		} else if (obj instanceof Location)
			return SerializeingLocation.serializeLocYaw((Location) obj);

		else if (obj instanceof UUID)
			return obj.toString();

		else if (obj instanceof Enum<?>)
			return obj.toString();

		else if (obj instanceof CommandSender)
			return ((CommandSender) obj).getName();

		else if (obj instanceof World)
			return ((World) obj).getName();

		else if (obj instanceof PotionEffect)
			return ((PotionEffect) obj).serialize();

		else if (obj instanceof java.awt.Color)
			return "#" + ((java.awt.Color) obj).getRGB();

		else if (obj instanceof BaseComponent)
			return toJson((BaseComponent) obj);

		else if (obj instanceof BaseComponent[])
			return toJson((BaseComponent[]) obj);

		else if (obj instanceof HoverEvent) {
			final HoverEvent event = (HoverEvent) obj;
			final Map<String, Object> serialize = new HashMap<>();
			serialize.put("Action", event.getAction().name());
			serialize.put("Value", Arrays.stream(event.getValue().clone()).map(BaseComponent::toString).collect(Collectors.toList()));
			return serialize;
		} else if (obj instanceof ClickEvent) {
			final ClickEvent event = (ClickEvent) obj;
			final Map<String, Object> serialize = new HashMap<>();
			serialize.put("Action", event.getAction().name());
			serialize.put("Value", event.getValue());
			return serialize;
		} else if (obj instanceof Iterable || obj.getClass().isArray()) {
			final List<Object> serialized = new ArrayList<>();

			if (obj instanceof Iterable)
				for (final Object element : (Iterable<?>) obj)
					serialized.add(serialize(element));
			else
				for (final Object element : (Object[]) obj)
					serialized.add(serialize(element));

			return serialized;
		} else if (obj instanceof Map) {
			final Map<?, ?> oldMap = (Map<?, ?>) obj;
			final Map<Object, Object> newMap = new LinkedHashMap<>();

			for (final Map.Entry<?, ?> entry : oldMap.entrySet())
				newMap.put(serialize(entry.getKey()), serialize(entry.getValue()));

			return newMap;
		} else if (obj instanceof Integer || obj instanceof Double || obj instanceof Float || obj instanceof Long || obj instanceof Short
				|| obj instanceof String || obj instanceof Boolean || obj instanceof ItemStack || obj instanceof MemorySection
				|| obj instanceof Pattern)
			return obj;

		else if (obj instanceof org.bukkit.configuration.serialization.ConfigurationSerializable)
			return ((org.bukkit.configuration.serialization.ConfigurationSerializable) obj).serialize();
		else if (obj instanceof ConfigurationSerializable)
			return ((ConfigurationSerializable) obj).serialize();

		throw new SerializeFailedException("Does not know how to serialize " + obj.getClass().getSimpleName() + "! Does it extends ConfigSerializable? Data: " + obj);
	}

	private static String toJson(final BaseComponent... comps) {
		if (serverVersion < 8.8)
			return "{}";
		String json;
		try {
			json = ComponentSerializer.toString(comps);
		} catch (final Throwable var3) {
			json = (new Gson()).toJson((new TextComponent(comps)).toLegacyText());
		}

		return json;
	}

	public static class SerializeFailedException extends RuntimeException {
		private static final long serialVersionUID = 2L;

		public SerializeFailedException(final String reason) {
			super(reason);
		}

	}
}
