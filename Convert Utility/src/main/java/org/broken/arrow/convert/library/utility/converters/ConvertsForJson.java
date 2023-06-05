package org.broken.arrow.convert.library.utility.converters;

import com.google.gson.Gson;
import org.broken.arrow.convert.library.SerializeData;
import org.bukkit.Location;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Convert list to json string and back to list agin.
 */
public class ConvertsForJson {

	private final SerializeData serializer;

	public ConvertsForJson(final SerializeData serializer) {
		this.serializer = serializer;
	}

	/**
	 * Serialize list to json to easy store it inside a database.
	 * Use {@link #convertFromJsonList(Class, String)} to get back a list.
	 *
	 * @param key       key to the json to acces it.
	 * @param arrayList the list you want to convert.
	 * @param <T>       type of class.
	 * @return json string.
	 */
	public <T> String convertToJsonList(final String key, final List<T> arrayList) {
		final Map<String, List<Object>> maps = new HashMap<>();
		final Gson gson = new Gson();
		final List<Object> serializeList = new ArrayList<>();
		if (arrayList != null)
			for (final T value : arrayList) {
				if (value instanceof Location)
					serializeList.add(serializer.serialize((Location) value));
				else if (value instanceof UUID)
					serializeList.add(String.valueOf(value));
				else
					serializeList.add(serializer.serialize(value));
			}
		maps.put(key, serializeList);

		return gson.toJson(maps);
	}

	public <T> List<T> convertFromJsonList(final Class<T> classof, final String Inputmap) {
		final ArrayList<T> arrayList = new ArrayList<>();
		final Gson gson = new Gson();
		final Map<String, List<Object>> map = gson.fromJson(Inputmap, (Type) Map.class);
		if (map != null) {
			final Map<String, List<Object>> mapList = new HashMap<>(map);
			for (final Map.Entry<String, List<Object>> entry : mapList.entrySet())
				for (final Object deserilizedList : entry.getValue()) {
					final Location loc = SerializeingLocation.deserializeLoc(deserilizedList);
					if (classof == Location.class && loc != null)
						arrayList.add(classof.cast(loc));
					else if (classof == UUID.class)
						arrayList.add(classof.cast(UUID.fromString(deserilizedList.toString())));
					else if (classof.isInstance(deserilizedList))
						arrayList.add(classof.cast(deserilizedList));
				}
		}
		return arrayList;
	}

}
