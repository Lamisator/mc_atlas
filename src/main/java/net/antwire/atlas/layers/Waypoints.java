package net.antwire.atlas.layers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.antwire.atlas.Atlas;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Named places on the map, saved in {@code <world>/atlas/waypoints.json}. */
public final class Waypoints {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final List<Waypoint> LIST = new ArrayList<>();
	private static Path file;

	public static final class Waypoint {
		public String name;
		public String dim;
		public int x, y, z;
		public String color = "#ffcc33";
		public String by = "";
	}

	private Waypoints() {
	}

	public static void load(Path root) {
		file = root.resolve("waypoints.json");
		LIST.clear();
		if (Files.exists(file)) {
			try (Reader r = Files.newBufferedReader(file)) {
				List<Waypoint> l = GSON.fromJson(r, new TypeToken<List<Waypoint>>() {
				}.getType());
				if (l != null) l.stream().filter(w -> w != null && w.name != null && w.dim != null).forEach(LIST::add);
			} catch (Exception e) {
				Atlas.LOGGER.error("Could not read {}", file, e);
			}
		}
		publish();
	}

	public static List<Waypoint> all() {
		return LIST;
	}

	public static Waypoint find(String name) {
		return LIST.stream().filter(w -> w.name.equalsIgnoreCase(name)).findFirst().orElse(null);
	}

	public static void add(Waypoint w) {
		LIST.removeIf(o -> o.name.equalsIgnoreCase(w.name));
		LIST.add(w);
		save();
	}

	public static boolean remove(String name) {
		boolean r = LIST.removeIf(o -> o.name.equalsIgnoreCase(name));
		if (r) save();
		return r;
	}

	private static void save() {
		publish();
		if (file == null) return;
		try {
			Files.createDirectories(file.getParent());
			try (Writer w = Files.newBufferedWriter(file)) {
				GSON.toJson(LIST, w);
			}
		} catch (Exception e) {
			Atlas.LOGGER.error("Could not write {}", file, e);
		}
	}

	private static void publish() {
		LayerData.put("waypoints", GSON.toJson(LIST));
	}
}
