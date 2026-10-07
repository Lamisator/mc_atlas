package net.antwire.atlas;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** config/atlas.json. */
public class AtlasConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("atlas.json");
	private static AtlasConfig instance = new AtlasConfig();

	/** Address and port of the web map. 0.0.0.0 = every network interface. */
	public String bind = "0.0.0.0";
	public int port = 8123;
	/** Title of the web page. */
	public String title = "Atlas";
	/** Dimensions that are mapped. */
	public List<String> dimensions = new ArrayList<>(List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"));
	/** Render every saved chunk once when no map exists yet (otherwise only what players visit and change). */
	public boolean fullRenderOnStart = true;
	/** Milliseconds per server tick the renderer may use for loading and drawing chunks. */
	public int renderBudgetMs = 8;
	/** Chunks around each player that are redrawn regularly (to catch building), and how often in seconds. */
	public int liveRadiusChunks = 6;
	public int liveIntervalSeconds = 20;
	/** Show players on the map, and players that are hidden from it. */
	public boolean showPlayers = true;
	public List<String> hiddenPlayers = new ArrayList<>();
	/** Radiation overlay: dose rate grid spacing in blocks and update interval in seconds. */
	public int radiationSpacing = 8;
	public int radiationIntervalSeconds = 30;
	/** Broadcast reception overlay: grid spacing in blocks, its extent around each transmitter, update interval. */
	public int coverageSpacing = 64;
	public int coverageRange = 2048;
	public int coverageIntervalSeconds = 600;

	public static AtlasConfig get() {
		return instance;
	}

	public static void load() {
		AtlasConfig c = null;
		if (Files.exists(PATH)) {
			try (Reader r = Files.newBufferedReader(PATH)) {
				c = GSON.fromJson(r, AtlasConfig.class);
			} catch (Exception e) {
				Atlas.LOGGER.error("Could not read {}, using defaults", PATH, e);
			}
		}
		if (c == null) c = new AtlasConfig();
		if (c.dimensions == null) c.dimensions = new ArrayList<>();
		if (c.hiddenPlayers == null) c.hiddenPlayers = new ArrayList<>();
		c.renderBudgetMs = Math.clamp(c.renderBudgetMs, 1, 40);
		c.radiationSpacing = Math.clamp(c.radiationSpacing, 2, 64);
		c.coverageSpacing = Math.clamp(c.coverageSpacing, 8, 256);
		instance = c;
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer w = Files.newBufferedWriter(PATH)) {
				GSON.toJson(c, w);
			}
		} catch (Exception e) {
			Atlas.LOGGER.error("Could not write {}", PATH, e);
		}
	}
}
