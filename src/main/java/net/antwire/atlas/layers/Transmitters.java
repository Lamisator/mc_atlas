package net.antwire.atlas.layers;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.antwire.atlas.Atlas;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Where Ham Radio radios stand, per dimension ({@code <world>/atlas/transmitters.json}): found by the full render in
 * the saved chunks and whenever one is loaded, so broadcast coverage can be computed while nobody is near them.
 * Independent of Ham Radio itself; {@link BroadcastLayer} decides which of them are broadcast transmitters.
 */
public final class Transmitters {
	private static final Gson GSON = new Gson();
	private static final Map<String, Set<Long>> POSITIONS = new HashMap<>();
	private static Path file;
	private static boolean dirty;

	private Transmitters() {
	}

	public static synchronized void load(Path root) {
		file = root.resolve("transmitters.json");
		POSITIONS.clear();
		if (Files.exists(file)) {
			try (Reader r = Files.newBufferedReader(file)) {
				Map<String, Set<Long>> m = GSON.fromJson(r, new TypeToken<Map<String, LinkedHashSet<Long>>>() {
				}.getType());
				if (m != null) POSITIONS.putAll(m);
			} catch (Exception e) {
				Atlas.LOGGER.warn("Could not read {}: {}", file, e.toString());
			}
		}
	}

	public static synchronized void add(String dim, long pos) {
		if (POSITIONS.computeIfAbsent(dim, d -> new LinkedHashSet<>()).add(pos)) dirty = true;
	}

	public static synchronized void remove(String dim, long pos) {
		Set<Long> s = POSITIONS.get(dim);
		if (s != null && s.remove(pos)) dirty = true;
	}

	public static synchronized Set<Long> in(String dim) {
		return Set.copyOf(POSITIONS.getOrDefault(dim, Set.of()));
	}

	public static synchronized void save() {
		if (!dirty || file == null) return;
		dirty = false;
		try {
			Files.createDirectories(file.getParent());
			try (Writer w = Files.newBufferedWriter(file)) {
				GSON.toJson(POSITIONS, w);
			}
		} catch (Exception e) {
			Atlas.LOGGER.warn("Could not write {}: {}", file, e.toString());
		}
	}
}
