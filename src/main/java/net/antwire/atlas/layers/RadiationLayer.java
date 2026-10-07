package net.antwire.atlas.layers;

import com.google.gson.stream.JsonWriter;
import dev.radiation.config.RadiationConfig;
import dev.radiation.world.RadiationSources;
import dev.radiation.world.RadiationTracker;
import net.antwire.atlas.AtlasConfig;
import net.antwire.atlas.render.Renderer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Radiation (only loaded when the Radiation mod is installed): zones, point sources (with Fission's drifting clouds and
 * their fallout), radiating blocks and waste barrels, and a grid of measured dose rates a metre and a half above the
 * ground around all of them - shielding included, as a Geiger counter would read it.
 */
final class RadiationLayer {
	/** Below this nothing is drawn. */
	private static final float FLOOR = 0.002F;
	private static final int MAX_CELLS = 60_000;
	private static final double MAX_REACH = 320;

	/** A dose-rate grid being measured, a slice per tick. */
	private static final class Job {
		final ServerLevel level;
		final List<long[]> cells;
		final int step;
		int next;
		final List<float[]> found = new ArrayList<>();

		Job(ServerLevel level, List<long[]> cells, int step) {
			this.level = level;
			this.cells = cells;
			this.step = step;
		}
	}

	private static Job job;

	private RadiationLayer() {
	}

	/** The list of sources (cheap, often). */
	static void update(ServerLevel level) throws IOException {
		RadiationSources sources = RadiationTracker.sources();
		String dim = RadiationTracker.dimensionId(level);
		long now = level.getGameTime();
		StringWriter out = new StringWriter();
		JsonWriter w = new JsonWriter(out);
		w.beginObject();
		w.name("zones").beginArray();
		for (RadiationSources.Zone z : sources.zones) {
			if (!z.dimension.equals(dim)) continue;
			w.beginObject().name("name").value(z.name).name("minX").value(z.minX).name("minZ").value(z.minZ).name("maxX").value(z.maxX)
					.name("maxZ").value(z.maxZ).name("minY").value(z.minY).name("maxY").value(z.maxY).name("rads").value(z.rads).endObject();
		}
		w.endArray();
		w.name("sources").beginArray();
		for (RadiationSources.PointSource s : sources.sources) {
			if (!s.dimension.equals(dim)) continue;
			String kind = s.name.startsWith("radioactive_cloud") || s.name.startsWith("fission_cloud") ? "cloud" : s.name.startsWith("fallout") ? "fallout"
					: s.name.startsWith("fission_release") ? "release" : "source";
			w.beginObject().name("name").value(s.name).name("kind").value(kind).name("x").value(r1(s.x)).name("y").value(r1(s.y)).name("z").value(r1(s.z))
					.name("rads").value(r4(s.radsAt(now))).name("radius").value(r1(s.radius)).endObject();
		}
		w.endArray();
		w.name("emitters").beginArray();
		for (RadiationSources.Emitter e : sources.emitters) {
			if (!e.dimension.equals(dim)) continue;
			w.beginArray().value(e.x).value(e.y).value(e.z).value(r4(e.rads)).value(e.block).endArray();
		}
		w.endArray();
		w.name("barrels").beginArray();
		for (RadiationSources.Barrel b : sources.barrels) {
			if (!b.dimension.equals(dim)) continue;
			w.beginArray().value(b.x).value(b.y).value(b.z).endArray();
		}
		w.endArray();
		w.name("barrelRads").value(RadiationConfig.get().barrelRads);
		w.endObject();
		w.close();
		LayerData.put("radiation/" + Renderer.key(level), out.toString());
	}

	/** Starts measuring a new dose-rate grid for a dimension (if none is being measured). */
	static void startRaster(ServerLevel level) {
		if (job != null) return;
		int step = AtlasConfig.get().radiationSpacing;
		RadiationSources sources = RadiationTracker.sources();
		String dim = RadiationTracker.dimensionId(level);
		long now = level.getGameTime();
		Set<Long> keys = new LinkedHashSet<>();
		for (RadiationSources.Zone z : sources.zones) {
			if (z.dimension.equals(dim) && z.rads >= FLOOR) box(keys, z.minX, z.minZ, z.maxX, z.maxZ, step);
		}
		for (RadiationSources.PointSource s : sources.sources) {
			if (!s.dimension.equals(dim)) continue;
			float rads = s.radsAt(now);
			if (rads < FLOOR) continue;
			double lo = 0, hi = Math.min(s.radius, MAX_REACH);
			for (int i = 0; i < 18; i++) {
				double mid = (lo + hi) / 2;
				if (rads * s.falloff.apply(mid, s.radius) >= FLOOR) lo = mid; else hi = mid;
			}
			round(keys, s.x, s.z, hi, step);
		}
		for (RadiationSources.Emitter e : sources.emitters) {
			if (!e.dimension.equals(dim) || e.rads * 4 < FLOOR) continue;
			round(keys, e.x + 0.5, e.z + 0.5, Math.min(Math.min(e.radius, Math.sqrt(e.rads / FLOOR)), MAX_REACH), step);
		}
		RadiationConfig rc = RadiationConfig.get();
		if (rc.barrelRads >= FLOOR) {
			for (RadiationSources.Barrel b : sources.barrels) {
				if (b.dimension.equals(dim)) round(keys, b.x + 0.5, b.z + 0.5, Math.min(rc.barrelRadius, MAX_REACH), step);
			}
		}
		List<long[]> cells = new ArrayList<>();
		for (long k : keys) {
			if (cells.size() >= MAX_CELLS) break;
			cells.add(new long[]{(int) (k >> 32), (int) k});
		}
		job = new Job(level, cells, step);
	}

	private static void box(Set<Long> keys, double x0, double z0, double x1, double z1, int step) {
		for (long x = Math.floorDiv((long) Math.floor(x0), step); x <= Math.floorDiv((long) Math.floor(x1), step); x++) {
			for (long z = Math.floorDiv((long) Math.floor(z0), step); z <= Math.floorDiv((long) Math.floor(z1), step); z++) {
				keys.add((x * step << 32) | ((z * step) & 0xFFFFFFFFL));
			}
		}
	}

	private static void round(Set<Long> keys, double cx, double cz, double r, int step) {
		box(keys, cx - r, cz - r, cx + r, cz + r, step);
	}

	/** Measures some cells; publishes the grid when done. @return whether a job is still running */
	static boolean work(long deadline) throws IOException {
		if (job == null) return false;
		Job j = job;
		while (j.next < j.cells.size() && System.nanoTime() < deadline) {
			long[] c = j.cells.get(j.next++);
			int x = (int) c[0] + j.step / 2, z = (int) c[1] + j.step / 2;
			int y = Terrain.surface(j.level, x, z);
			float rate = RadiationTracker.exposureAt(j.level, new Vec3(x + 0.5, y + 1.5, z + 0.5), null);
			if (rate >= FLOOR) j.found.add(new float[]{c[0], c[1], rate});
		}
		if (j.next < j.cells.size()) return true;
		StringWriter out = new StringWriter();
		JsonWriter w = new JsonWriter(out);
		w.beginObject().name("step").value(j.step).name("time").value(j.level.getGameTime()).name("cells").beginArray();
		for (float[] f : j.found) {
			w.beginArray().value((int) f[0]).value((int) f[1]).value(r4(f[2])).endArray();
		}
		w.endArray().endObject();
		w.close();
		LayerData.put("doserate/" + Renderer.key(j.level), out.toString());
		job = null;
		return false;
	}

	private static final java.util.regex.Pattern PART = java.util.regex.Pattern.compile("^\\s*(.*?): ([0-9.]+) rad/s$");

	/**
	 * What a Geiger counter would read a metre and a half above the ground at x, z (or at y, if given), and where it comes
	 * from. Server thread only.
	 */
	static String measure(ServerLevel level, int x, int z, Integer y) throws IOException {
		boolean loaded = level.hasChunk(x >> 4, z >> 4);
		int ground = Terrain.surface(level, x, z);
		double at = y != null ? y + 0.5 : ground + 1.5;
		List<net.minecraft.network.chat.Component> breakdown = new ArrayList<>();
		float rate = RadiationTracker.exposureAt(level, new Vec3(x + 0.5, at, z + 0.5), breakdown);
		List<Object[]> parts = new ArrayList<>();
		for (var c : breakdown) {
			java.util.regex.Matcher m = PART.matcher(c.getString());
			if (!m.matches()) continue;
			double v = Double.parseDouble(m.group(2));
			if (v >= 0.0001) parts.add(new Object[]{label(m.group(1)), v});
		}
		parts.sort((a, b) -> Double.compare((double) b[1], (double) a[1]));
		StringWriter out = new StringWriter();
		JsonWriter w = new JsonWriter(out);
		w.beginObject().name("x").value(x).name("z").value(z).name("y").value(Math.floor(at * 10) / 10).name("ground").value(ground)
				.name("loaded").value(loaded).name("rads").value(r4(rate)).name("maxRads").value(RadiationConfig.get().maxRads);
		w.name("parts").beginArray();
		for (int i = 0; i < Math.min(6, parts.size()); i++) {
			w.beginArray().value((String) parts.get(i)[0]).value(r4((double) parts.get(i)[1])).endArray();
		}
		w.endArray().endObject();
		w.close();
		return out.toString();
	}

	/** "source radioactive_cloud_12" and the like, as people would say it. */
	private static String label(String s) {
		if (s.startsWith("source ")) {
			String n = s.substring(7);
			if (n.startsWith("radioactive_cloud") || n.startsWith("fission_cloud")) return "radioactive cloud";
			if (n.startsWith("fallout")) return "fallout";
			if (n.startsWith("fission_release")) return "open reactor core";
			return "source " + n;
		}
		if (s.startsWith("zone ")) return s;
		int colon = s.indexOf(':');
		// "fission:corium at 1 2 3" -> "corium at 1 2 3"
		return colon > 0 && colon < s.indexOf(' ') ? s.substring(colon + 1).replace('_', ' ') : s;
	}

	private static double r1(double v) {
		return Math.round(v * 10) / 10.0;
	}

	private static double r4(double v) {
		return v >= 1 ? Math.round(v * 100) / 100.0 : Math.round(v * 10000) / 10000.0;
	}
}
