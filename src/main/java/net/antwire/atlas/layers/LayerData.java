package net.antwire.atlas.layers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The latest JSON of every layer, built on the server thread and served by the web threads. */
public final class LayerData {
	private static final Map<String, String> DATA = new ConcurrentHashMap<>();

	private LayerData() {
	}

	/** @param name e.g. "live", "grid/minecraft_overworld" */
	public static void put(String name, String json) {
		DATA.put(name, json);
	}

	public static String get(String name) {
		return DATA.getOrDefault(name, "null");
	}

	public static void clear() {
		DATA.clear();
	}
}
