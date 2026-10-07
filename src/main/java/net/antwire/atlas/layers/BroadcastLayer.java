package net.antwire.atlas.layers;

import com.google.gson.stream.JsonWriter;
import dev.hamradio.antenna.AntennaType;
import dev.hamradio.block.RadioBlockEntity;
import dev.hamradio.power.GridPower;
import dev.hamradio.propagation.Propagation;
import dev.hamradio.propagation.Station;
import dev.hamradio.radio.BroadcastBand;
import dev.hamradio.radio.RadioModel;
import dev.hamradio.radio.RadioNetwork;
import dev.hamradio.radio.RadioSettings;
import net.antwire.atlas.Atlas;
import net.antwire.atlas.AtlasConfig;
import net.antwire.atlas.render.Renderer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Broadcast reception (only loaded when Ham Radio is installed): every broadcast transmitter and studio on air, and
 * for each a grid of the signal-to-noise ratio a portable radio (rubber duck antenna, 1.5 m above the ground) would
 * get, computed with Ham Radio's own antenna and propagation model - ground wave, terrain, and the sky wave of the
 * moment for long, medium and short wave.
 */
final class BroadcastLayer {
	private static Field radios;

	private record Tx(String name, String model, int x, int y, int z, long hz, String band, String mode, int power, double radiated,
			float minSnr, Station station, dev.hamradio.radio.Mode m) {
	}

	/**
	 * Coverage is measured coarse to fine: every 4th grid point first (published after a minute or two), then every
	 * 2nd, then all; each pass adds only the points the previous ones did not have.
	 */
	private static final class Job {
		final ServerLevel level;
		final List<Tx> stations;
		int station, index, pass = 0;
		final List<List<int[]>> found = new ArrayList<>();
		final int step, range;
		long started = System.nanoTime(), points;
		long cpu;

		Job(ServerLevel level, List<Tx> stations, int step, int range) {
			this.level = level;
			this.stations = stations;
			this.step = step;
			this.range = range;
			for (int i = 0; i < stations.size(); i++) this.found.add(new ArrayList<>());
		}

		/** Grid stride of the current pass: 4, 2, 1 (in grid steps). */
		int stride() {
			return 4 >> this.pass;
		}
	}

	private static Job job;

	private BroadcastLayer() {
	}

	@SuppressWarnings("unchecked")
	private static Collection<RadioBlockEntity> radios() {
		try {
			if (radios == null) {
				radios = RadioNetwork.class.getDeclaredField("RADIOS");
				radios.setAccessible(true);
			}
			return List.copyOf((Collection<RadioBlockEntity>) radios.get(null));
		} catch (ReflectiveOperationException | RuntimeException e) {
			Atlas.LOGGER.warn("Atlas: cannot see Ham Radio's radios ({}); no broadcast layer", e.toString());
			return List.of();
		}
	}

	/** Last known details of each transmitter, for when its chunk is not loaded. */
	private static final java.util.Map<Long, Tx> LAST = new java.util.HashMap<>();

	/**
	 * The broadcast transmitters on air: those Ham Radio has loaded, plus the remembered ones ({@link Transmitters}) -
	 * with {@code load}, their chunk is loaded for it (it lies inside the finished map, so nothing is generated); without,
	 * their last known details are used.
	 */
	private static List<Tx> stations(ServerLevel level, boolean load) {
		String dim = Renderer.key(level);
		java.util.Map<Long, RadioBlockEntity> found = new java.util.LinkedHashMap<>();
		for (RadioBlockEntity b : radios()) {
			if (!b.isRemoved() && b.getLevel() == level) found.put(b.getBlockPos().asLong(), b);
		}
		List<Tx> out = new ArrayList<>();
		for (long p : Transmitters.in(dim)) {
			if (found.containsKey(p)) continue;
			net.minecraft.core.BlockPos bp = net.minecraft.core.BlockPos.of(p);
			if (!level.hasChunk(bp.getX() >> 4, bp.getZ() >> 4)) {
				if (!load) {
					Tx last = LAST.get(p);
					if (last != null) out.add(last);
					continue;
				}
				level.getChunk(bp.getX() >> 4, bp.getZ() >> 4);
			}
			if (level.getBlockEntity(bp) instanceof RadioBlockEntity b) found.put(p, b);
			else Transmitters.remove(dim, p);
		}
		for (RadioBlockEntity b : found.values()) {
			long key = b.getBlockPos().asLong();
			if (b.model() != RadioModel.BROADCAST && b.model() != RadioModel.STUDIO) {
				Transmitters.remove(dim, key);
				continue;
			}
			Transmitters.add(dim, key);
			// as configured: a transmitter whose chunk was just loaded for this is not fed by the grid yet
			RadioSettings s = GridPower.effective(b);
			if (!s.on() && b.settings().on()) s = b.settings();
			long f = s.frequency();
			if (!b.beacon || !s.on() || !b.model().canTransmit(f, s.mode())) {
				LAST.remove(key);
				continue;
			}
			Station st = Station.forRadio(level, b.getBlockPos(), b.model());
			double radiated = st.chain(f, s.power()).radiated();
			BroadcastBand band = BroadcastBand.of(f);
			var p = b.getBlockPos();
			Tx tx = new Tx(b.stationName, b.model().name().toLowerCase(Locale.ROOT), p.getX(), p.getY(), p.getZ(), f,
					band == null ? "" : band.label, s.mode().label, s.power(), radiated, s.mode().minSnr, st, s.mode());
			LAST.put(key, tx);
			out.add(tx);
		}
		Transmitters.save();
		return out;
	}

	static String describe() {
		Job j = job;
		return j == null ? "idle" : String.format(Locale.ROOT, "pass %d, station %d of %d, %d points, %d ms", j.pass + 1, j.station + 1, j.stations.size(),
				j.points, j.cpu / 1_000_000);
	}

	/** The transmitters (cheap, often). */
	static void update(ServerLevel level) throws IOException {
		LayerData.put("stations/" + Renderer.key(level), json(level, stations(level, false), null, 0));
	}

	/** @return whether there was anything to measure */
	static boolean startCoverage(ServerLevel level) {
		if (job != null) return true;
		List<Tx> st = stations(level, true);
		if (st.isEmpty()) {
			try {
				LayerData.put("coverage/" + Renderer.key(level), json(level, st, null, 0));
			} catch (IOException ignored) {
			}
			return false;
		}
		job = new Job(level, st, AtlasConfig.get().coverageSpacing, AtlasConfig.get().coverageRange);
		Atlas.LOGGER.info("Atlas: measuring the broadcast coverage of {} ({} transmitters)", Renderer.key(level), st.size());
		return true;
	}

	static boolean work(long deadline) throws IOException {
		if (job == null) return false;
		Job j = job;
		long t0 = System.nanoTime();
		int side = 2 * j.range / j.step + 5;
		while (j.station < j.stations.size() && System.nanoTime() < deadline) {
			Tx tx = j.stations.get(j.station);
			if (j.index >= side * side) {
				j.station++;
				j.index = 0;
				continue;
			}
			int gx = j.index % side, gz = j.index / side;
			j.index++;
			int stride = j.stride();
			if (gx % stride != 0 || gz % stride != 0 || j.pass > 0 && gx % (stride * 2) == 0 && gz % (stride * 2) == 0) continue;
			j.points++;
			// one grid for all stations, aligned to the coarsest pass, so their cells can be combined
			int cell = j.step * 4;
			int x = Math.floorDiv(tx.x - j.range, cell) * cell + gx * j.step, z = Math.floorDiv(tx.z - j.range, cell) * cell + gz * j.step;
			double dx = x - tx.x, dz = z - tx.z;
			if (dx * dx + dz * dz > (double) j.range * j.range) continue;
			int y = Terrain.surface(j.level, x, z);
			Station rx = Station.fixed(j.level, new Vec3(x + 0.5, y + 1.5, z + 0.5), AntennaType.RUBBER_DUCK, 1.5, 7L);
			Propagation.Link link = Propagation.compute(tx.station, rx, tx.hz, tx.radiated, 0, tx.m);
			if (link.snr() >= tx.minSnr - 10) {
				j.found.get(j.station).add(new int[]{x, z, (int) Math.round(link.snr())});
			}
		}
		j.cpu += System.nanoTime() - t0;
		if (j.station < j.stations.size()) return true;
		// a pass is done: publish what there is (at the pass's resolution), then refine
		int shown = j.step * j.stride();
		LayerData.put("coverage/" + Renderer.key(j.level), json(j.level, j.stations, j.found, shown));
		Atlas.LOGGER.info("Atlas: broadcast coverage of {} at {} blocks: {} points in {} ms of server time", Renderer.key(j.level), shown, j.points,
				j.cpu / 1_000_000);
		if (j.pass < 2) {
			j.pass++;
			j.station = 0;
			j.index = 0;
			return true;
		}
		job = null;
		return false;
	}

	private static String json(ServerLevel level, List<Tx> stations, List<List<int[]>> cells, int step) throws IOException {
		StringWriter out = new StringWriter();
		JsonWriter w = new JsonWriter(out);
		w.beginObject().name("time").value(level.getOverworldClockTime() % 24000).name("step").value(step)
				.name("range").value(cells == null ? 0 : AtlasConfig.get().coverageRange).name("stations").beginArray();
		for (int i = 0; i < stations.size(); i++) {
			Tx t = stations.get(i);
			w.beginObject().name("name").value(t.name).name("model").value(t.model).name("x").value(t.x).name("y").value(t.y).name("z").value(t.z)
					.name("hz").value(t.hz).name("band").value(t.band).name("mode").value(t.mode).name("power").value(t.power)
					.name("radiated").value(Math.round(t.radiated)).name("minSnr").value(t.minSnr);
			if (cells != null) {
				w.name("cells").beginArray();
				for (int[] c : cells.get(i)) w.beginArray().value(c[0]).value(c[1]).value(c[2]).endArray();
				w.endArray();
			}
			w.endObject();
		}
		w.endArray().endObject();
		w.close();
		return out.toString();
	}
}
