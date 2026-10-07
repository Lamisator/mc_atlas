package net.antwire.atlas.render;

import net.antwire.atlas.Atlas;
import net.antwire.atlas.AtlasConfig;
import net.antwire.atlas.layers.Transmitters;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps the tiles up to date.
 * <ul>
 * <li>Full render (first start, or {@code /atlas render}): a background thread reads every chunk saved in the region
 * files and draws it from its saved sections. Nothing is loaded into the world, so nothing is generated either; chunks
 * whose generation was never finished (the ragged edge of a map) are skipped.</li>
 * <li>On the server thread, within a time budget per tick: chunks that load or unload, and a regular sweep of the
 * chunks around each player, which catches building.</li>
 * </ul>
 * A writer thread saves changed tiles every few seconds.
 */
public final class Renderer {
	public static final class Dim {
		public final ServerLevel level;
		public final String key;
		public final TileStore tiles;
		final boolean ceiling;
		final Set<Long> touched = new LinkedHashSet<>();
		public volatile int fullTotal;
		public final AtomicInteger fullDone = new AtomicInteger();
		volatile boolean fullRunning;

		Dim(ServerLevel level, String key, TileStore tiles) {
			this.level = level;
			this.key = key;
			this.tiles = tiles;
			this.ceiling = level.dimensionType().hasCeiling();
		}

		public boolean fullRunning() {
			return this.fullRunning;
		}
	}

	private static final Map<String, Dim> DIMS = new HashMap<>();
	private static ScheduledExecutorService writer;
	private static ExecutorService full;
	private static volatile boolean stopping;
	private static Path root;
	private static long sweepAt;
	private static final Deque<long[]> SWEEP = new ArrayDeque<>();

	private Renderer() {
	}

	public static Path root() {
		return root;
	}

	public static String key(ServerLevel level) {
		return level.dimension().identifier().toString().replace(':', '_');
	}

	public static Map<String, Dim> dims() {
		return DIMS;
	}

	public static void start(MinecraftServer server) {
		AtlasConfig config = AtlasConfig.get();
		stopping = false;
		root = server.getWorldPath(LevelResource.ROOT).resolve("atlas");
		DIMS.clear();
		writer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "Atlas tile writer"));
		writer.scheduleWithFixedDelay(Renderer::flushAll, 3, 3, TimeUnit.SECONDS);
		full = Executors.newSingleThreadExecutor(r -> daemon(r, "Atlas full render"));
		for (ServerLevel level : server.getAllLevels()) {
			if (!config.dimensions.contains(level.dimension().identifier().toString())) continue;
			String key = key(level);
			Dim d = new Dim(level, key, new TileStore(root.resolve("tiles").resolve(key)));
			DIMS.put(key, d);
			if (config.fullRenderOnStart && !Files.isDirectory(d.tiles.dir().resolve("0"))) {
				full(d);
			}
		}
	}

	private static Thread daemon(Runnable r, String name) {
		Thread t = new Thread(r, name);
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY + 1);
		return t;
	}

	public static void stop() {
		stopping = true;
		if (full != null) {
			full.shutdownNow();
			full = null;
		}
		if (writer != null) {
			writer.shutdown();
			try {
				writer.awaitTermination(10, TimeUnit.SECONDS);
			} catch (InterruptedException ignored) {
			}
			flushAll();
			writer = null;
		}
		DIMS.clear();
	}

	private static void flushAll() {
		for (Dim d : List.copyOf(DIMS.values())) {
			try {
				d.tiles.flush();
			} catch (Exception e) {
				Atlas.LOGGER.error("Writing tiles of {} failed", d.key, e);
			}
		}
	}

	/** Queues a full render of a dimension from its saved chunks. @return how many chunks are saved */
	public static int full(Dim d) {
		Path regions = DimensionType.getStorageFolder(d.level.dimension(), d.level.getServer().getWorldPath(LevelResource.ROOT)).resolve("region");
		List<Long> found = new ArrayList<>();
		if (Files.isDirectory(regions)) {
			try (var files = Files.list(regions)) {
				for (Path f : (Iterable<Path>) files::iterator) {
					String n = f.getFileName().toString();
					String[] parts = n.split("\\.");
					if (parts.length != 4 || !n.endsWith(".mca")) continue;
					int rx = Integer.parseInt(parts[1]), rz = Integer.parseInt(parts[2]);
					try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
						if (raf.length() < 4096) continue;
						for (int i = 0; i < 1024; i++) {
							if (raf.readInt() != 0) {
								found.add(ChunkPos.pack(rx * 32 + (i & 31), rz * 32 + (i >> 5)));
							}
						}
					}
				}
			} catch (IOException | NumberFormatException e) {
				Atlas.LOGGER.warn("Could not list the region files of {}: {}", d.key, e.toString());
			}
		}
		// north to south, so the relief of a chunk's first row can use the chunk north of it
		found.sort(Comparator.comparingInt((Long c) -> ChunkPos.getZ(c)).thenComparingInt(c -> ChunkPos.getX(c)));
		d.fullTotal = found.size();
		d.fullDone.set(0);
		d.fullRunning = !found.isEmpty();
		Atlas.LOGGER.info("Atlas: rendering {} saved chunks of {}", found.size(), d.key);
		if (!found.isEmpty() && full != null) {
			full.submit(() -> renderSaved(d, found));
		}
		return found.size();
	}

	/** Runs on the full render thread. */
	private static void renderSaved(Dim d, List<Long> chunks) {
		Map<Long, int[]> southRows = new HashMap<>();
		int skipped = 0, old = 0;
		int version = SharedConstants.getCurrentVersion().dataVersion().version();
		long t0 = System.currentTimeMillis();
		for (long c : chunks) {
			if (stopping || Thread.currentThread().isInterrupted()) return;
			int cx = ChunkPos.getX(c), cz = ChunkPos.getZ(c);
			try {
				Optional<CompoundTag> tag = d.level.getChunkSource().chunkMap.read(new ChunkPos(cx, cz)).join();
				if (tag.isEmpty() || !finished(tag.get())) {
					skipped++;
					continue;
				}
				if (tag.get().getIntOr("DataVersion", version) != version) {
					// saved by another version: needs upgrading, which only loading does; drawn when it loads
					old++;
					continue;
				}
				SerializableChunkData data = SerializableChunkData.parse(d.level, d.level.palettedContainerFactory(), tag.get());
				if (data == null) {
					skipped++;
					continue;
				}
				int sections = d.level.getSectionsCount();
				LevelChunkSection[] arr = new LevelChunkSection[sections];
				int minSection = d.level.getMinSectionY();
				for (SerializableChunkData.SectionData s : data.sectionData()) {
					int i = s.y() - minSection;
					if (i >= 0 && i < sections) arr[i] = s.chunkSection();
				}
				// remember where radios stand (broadcast coverage needs to find them while nobody is near)
				for (CompoundTag be : data.blockEntities()) {
					if (be.getStringOr("id", "").equals("hamradio:radio")) {
						Transmitters.add(d.key, BlockPos.asLong(be.getIntOr("x", 0), be.getIntOr("y", 0), be.getIntOr("z", 0)));
					}
				}
				ChunkColors.Result r = ChunkColors.render(ChunkColors.of(arr, minSection, d.ceiling), southRows.remove(ChunkPos.pack(cx, cz - 1)), d.ceiling);
				southRows.put(c, r.southRow());
				d.tiles.put(cx, cz, r.colors());
			} catch (Exception e) {
				skipped++;
			} finally {
				d.fullDone.incrementAndGet();
			}
		}
		d.fullRunning = false;
		Transmitters.save();
		Atlas.LOGGER.info("Atlas: {} rendered in {} s ({} unfinished chunks skipped{})", d.key, (System.currentTimeMillis() - t0) / 1000,
				skipped, old > 0 ? ", " + old + " from older versions wait until they are loaded" : "");
	}

	private static boolean finished(CompoundTag tag) {
		String status = tag.getStringOr("Status", "");
		return status.equals("minecraft:full") || status.equals("full");
	}

	private static void draw(Dim d, LevelChunk chunk) {
		LevelChunk n = d.level.getChunkSource().getChunkNow(chunk.getPos().x(), chunk.getPos().z() - 1);
		int[] north = null;
		if (n != null && !d.ceiling) {
			north = new int[16];
			for (int x = 0; x < 16; x++) north[x] = n.getHeight(Heightmap.Types.WORLD_SURFACE, x, 15) - 1;
		}
		d.tiles.put(chunk.getPos().x(), chunk.getPos().z(), ChunkColors.render(ChunkColors.of(chunk, d.ceiling), north, d.ceiling).colors());
	}

	public static void chunkLoaded(ServerLevel level, LevelChunk chunk) {
		Dim d = DIMS.get(key(level));
		if (d != null) {
			d.touched.add(chunk.getPos().pack());
		}
	}

	public static void chunkUnloaded(ServerLevel level, LevelChunk chunk) {
		Dim d = DIMS.get(key(level));
		if (d != null) {
			draw(d, chunk);
			d.touched.remove(chunk.getPos().pack());
		}
	}

	public static void tick(MinecraftServer server) {
		AtlasConfig config = AtlasConfig.get();
		long deadline = System.nanoTime() + config.renderBudgetMs * 1_000_000L;
		for (Dim d : DIMS.values()) {
			var it = d.touched.iterator();
			while (it.hasNext() && System.nanoTime() < deadline) {
				long c = it.next();
				it.remove();
				LevelChunk chunk = d.level.getChunkSource().getChunkNow(ChunkPos.getX(c), ChunkPos.getZ(c));
				if (chunk != null) draw(d, chunk);
			}
		}
		long now = System.currentTimeMillis();
		if (SWEEP.isEmpty() && now >= sweepAt) {
			sweepAt = now + config.liveIntervalSeconds * 1000L;
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				Dim d = DIMS.get(key(p.level()));
				if (d == null) continue;
				int r = config.liveRadiusChunks;
				for (int dx = -r; dx <= r; dx++) {
					for (int dz = -r; dz <= r; dz++) {
						SWEEP.add(new long[]{d.key.hashCode(), p.chunkPosition().x() + dx, p.chunkPosition().z() + dz});
					}
				}
			}
		}
		while (!SWEEP.isEmpty() && System.nanoTime() < deadline) {
			long[] s = SWEEP.poll();
			for (Dim d : DIMS.values()) {
				if (d.key.hashCode() != s[0]) continue;
				LevelChunk chunk = d.level.getChunkSource().getChunkNow((int) s[1], (int) s[2]);
				if (chunk != null) draw(d, chunk);
			}
		}
	}

	/** Draws the loaded chunks around a position now (for /atlas render here). */
	public static int around(ServerLevel level, ChunkPos center, int radius) {
		Dim d = DIMS.get(key(level));
		if (d == null) return 0;
		int n = 0;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(center.x() + dx, center.z() + dz);
				if (chunk != null) {
					draw(d, chunk);
					n++;
				}
			}
		}
		return n;
	}
}
