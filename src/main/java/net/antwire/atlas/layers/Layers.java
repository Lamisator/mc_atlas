package net.antwire.atlas.layers;

import com.google.gson.stream.JsonWriter;
import net.antwire.atlas.Atlas;
import net.antwire.atlas.AtlasConfig;
import net.antwire.atlas.render.Renderer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Refreshes the layers: players every second, grids, radiation sources and transmitters every five seconds, the
 * dose-rate grid and the broadcast coverage in slices within a time budget per tick.
 */
public final class Layers {
	public static final boolean GRID = FabricLoader.getInstance().isModLoaded("gridworks");
	public static final boolean RADIATION = FabricLoader.getInstance().isModLoaded("radiation");
	public static final boolean BROADCAST = FabricLoader.getInstance().isModLoaded("hamradio");
	private static final int BUDGET_NS = 5_000_000;
	private static long ticks;
	private static long radiationAt, coverageAt;
	private static int radiationDim, coverageDim;
	private static boolean failedGrid, failedRadiation, failedBroadcast;
	/** Whether a dose-rate or coverage run was going on last tick: the next one is due an interval after it ends. */
	private static boolean radiationBusy, coverageBusy;

	private Layers() {
	}

	public static List<String> available() {
		List<String> l = new ArrayList<>(List.of("players", "waypoints"));
		if (GRID) l.add("grid");
		if (RADIATION) l.add("radiation");
		if (BROADCAST) l.add("broadcast");
		return l;
	}

	public static void reset() {
		ticks = 0;
		radiationAt = coverageAt = 0;
	}

	public static void tick(MinecraftServer server) {
		ticks++;
		AtlasConfig config = AtlasConfig.get();
		List<ServerLevel> levels = new ArrayList<>();
		for (var d : Renderer.dims().values()) levels.add(d.level);
		// the overworld first
		levels.sort(java.util.Comparator.comparing((ServerLevel l) -> l != server.overworld()));
		if (ticks % 20 == 0) {
			live(server);
		}
		if (ticks % 100 == 1) {
			for (ServerLevel level : levels) {
				if (GRID && !failedGrid) failedGrid = !guard("grid", () -> GridLayer.update(level));
				if (RADIATION && !failedRadiation) failedRadiation = !guard("radiation", () -> RadiationLayer.update(level));
				if (BROADCAST && !failedBroadcast) failedBroadcast = !guard("broadcast", () -> BroadcastLayer.update(level));
			}
		}
		if (levels.isEmpty()) return;
		long now = System.currentTimeMillis();
		long deadline = System.nanoTime() + BUDGET_NS;
		if (RADIATION && !failedRadiation) {
			failedRadiation = !guard("radiation", () -> {
				boolean busy = RadiationLayer.work(deadline);
				if (radiationBusy && !busy) {
					radiationAt = now + config.radiationIntervalSeconds * 1000L / levels.size();
				}
				radiationBusy = busy;
				if (!busy && now >= radiationAt) {
					radiationAt = now + config.radiationIntervalSeconds * 1000L / levels.size();
					RadiationLayer.startRaster(levels.get(radiationDim++ % levels.size()));
					radiationBusy = true;
				}
			});
		}
		if (BROADCAST && !failedBroadcast) {
			failedBroadcast = !guard("broadcast", () -> {
				boolean busy = BroadcastLayer.work(deadline);
				if (coverageBusy && !busy) {
					coverageAt = now + config.coverageIntervalSeconds * 1000L / levels.size();
				}
				coverageBusy = busy;
				if (!busy && now >= coverageAt) {
					// dimensions without transmitters are passed over at once; if none has any on air (the grid may not
					// be powered up yet), look again in half a minute
					boolean started = false;
					for (int i = 0; i < levels.size() && !started; i++) {
						started = BroadcastLayer.startCoverage(levels.get(coverageDim++ % levels.size()));
					}
					coverageAt = now + (started ? config.coverageIntervalSeconds * 1000L / levels.size() : 30_000L);
					coverageBusy = started;
				}
			});
		}
	}

	public static String coverageState() {
		return BROADCAST ? BroadcastLayer.describe() + (failedBroadcast ? " (failed)" : "") + ", next in " + Math.max(0, (coverageAt - System.currentTimeMillis()) / 1000) + " s" : "n/a";
	}

	/** Coverage and dose rates again now (after /atlas refresh). */
	public static void refreshSoon() {
		radiationAt = coverageAt = 0;
	}

	private interface Work {
		void run() throws Exception;
	}

	/** Runs a layer update; a layer that fails (another mod changed underneath) is switched off with one log line. */
	private static boolean guard(String layer, Work w) {
		try {
			w.run();
			return true;
		} catch (Throwable t) {
			Atlas.LOGGER.error("Atlas: the {} layer failed and is switched off until restart", layer, t);
			return false;
		}
	}

	private static void live(MinecraftServer server) {
		AtlasConfig config = AtlasConfig.get();
		try {
			StringWriter out = new StringWriter();
			JsonWriter w = new JsonWriter(out);
			w.beginObject();
			w.name("time").value(server.overworld().getOverworldClockTime() % 24000);
			w.name("weather").value(server.overworld().isThundering() ? "thunder" : server.overworld().isRaining() ? "rain" : "clear");
			w.name("players").beginArray();
			if (config.showPlayers) {
				for (ServerPlayer p : server.getPlayerList().getPlayers()) {
					String name = p.getScoreboardName();
					if (config.hiddenPlayers.stream().anyMatch(h -> h.equalsIgnoreCase(name)) || p.isSpectator()) continue;
					w.beginObject().name("name").value(name).name("dim").value(Renderer.key(p.level()))
							.name("x").value(Math.round(p.getX() * 10) / 10.0).name("y").value(Math.round(p.getY() * 10) / 10.0)
							.name("z").value(Math.round(p.getZ() * 10) / 10.0).name("yaw").value(Math.round(p.getYRot()))
							.name("health").value(Math.round(p.getHealth())).endObject();
				}
			}
			w.endArray();
			w.name("render").beginArray();
			for (var d : Renderer.dims().values()) {
				w.beginObject().name("dim").value(d.key).name("total").value(d.fullTotal).name("done").value(d.fullDone.get())
						.name("pending").value(d.tiles.pending()).endObject();
			}
			w.endArray();
			w.endObject();
			w.close();
			LayerData.put("live", out.toString());
		} catch (Exception e) {
			Atlas.LOGGER.warn("Atlas: live data failed: {}", e.toString());
		}
	}
}
