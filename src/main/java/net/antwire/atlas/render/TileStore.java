package net.antwire.atlas.render;

import net.antwire.atlas.Atlas;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The map of one dimension as PNG tiles: level 0 has one pixel per block, 256 x 256 blocks per tile; every level above
 * halves the resolution (level 6: one pixel per 64 blocks). Chunks are drawn into level-0 tiles in memory; a tile is
 * marked changed only if its pixels actually change. {@link #flush()} (on the writer thread) saves changed tiles and
 * rebuilds the levels above them.
 */
public final class TileStore {
	public static final int SIZE = 256;
	public static final int LEVELS = 6;
	private static final int CACHE = 96;

	private final Path dir;
	private final Map<Long, int[]> cache = new LinkedHashMap<>(64, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, int[]> eldest) {
			return this.size() > CACHE && !TileStore.this.dirty.contains(eldest.getKey());
		}
	};
	private final Set<Long> dirty = new HashSet<>();
	private long changedChunks;

	public TileStore(Path dir) {
		this.dir = dir;
	}

	public Path dir() {
		return this.dir;
	}

	public static long key(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}

	public static Path file(Path dir, int level, int x, int z) {
		return dir.resolve(Integer.toString(level)).resolve(x + "_" + z + ".png");
	}

	/** Draws one chunk (16 x 16 ARGB, row by row from north-west). @return whether anything changed */
	public synchronized boolean put(int chunkX, int chunkZ, int[] colors) {
		int tx = chunkX >> 4, tz = chunkZ >> 4;
		long k = key(tx, tz);
		int[] tile = this.tile(tx, tz, k);
		int ox = (chunkX & 15) * 16, oz = (chunkZ & 15) * 16;
		boolean changed = false;
		for (int z = 0; z < 16; z++) {
			int row = (oz + z) * SIZE + ox;
			for (int x = 0; x < 16; x++) {
				int c = colors[z * 16 + x];
				if (tile[row + x] != c) {
					tile[row + x] = c;
					changed = true;
				}
			}
		}
		if (changed) {
			this.dirty.add(k);
			this.changedChunks++;
		}
		return changed;
	}

	private int[] tile(int tx, int tz, long k) {
		int[] t = this.cache.get(k);
		if (t == null) {
			t = read(file(this.dir, 0, tx, tz));
			this.cache.put(k, t);
		}
		return t;
	}

	public synchronized int pending() {
		return this.dirty.size();
	}

	public synchronized long changedChunks() {
		return this.changedChunks;
	}

	/** Saves changed tiles and redraws the coarser levels above them. Runs on the writer thread. */
	public void flush() {
		Map<Long, int[]> todo = new HashMap<>();
		synchronized (this) {
			for (long k : this.dirty) {
				int[] t = this.cache.get(k);
				if (t != null) todo.put(k, t.clone());
			}
			this.dirty.clear();
		}
		if (todo.isEmpty()) {
			return;
		}
		Set<Long> parents = new HashSet<>();
		for (Map.Entry<Long, int[]> e : todo.entrySet()) {
			int tx = (int) (e.getKey() >> 32), tz = (int) (long) e.getKey();
			write(file(this.dir, 0, tx, tz), e.getValue());
			parents.add(key(tx >> 1, tz >> 1));
		}
		for (int level = 1; level <= LEVELS; level++) {
			Set<Long> next = new HashSet<>();
			for (long k : parents) {
				int px = (int) (k >> 32), pz = (int) k;
				write(file(this.dir, level, px, pz), this.downsample(level, px, pz));
				next.add(key(px >> 1, pz >> 1));
			}
			parents = next;
		}
	}

	/** One tile of a coarser level from the four tiles below it: each pixel the average of four. */
	private int[] downsample(int level, int px, int pz) {
		int[] out = new int[SIZE * SIZE];
		for (int q = 0; q < 4; q++) {
			int cx = px * 2 + (q & 1), cz = pz * 2 + (q >> 1);
			Path f = file(this.dir, level - 1, cx, cz);
			if (!Files.exists(f)) continue;
			int[] child = read(f);
			int ox = (q & 1) * SIZE / 2, oz = (q >> 1) * SIZE / 2;
			for (int z = 0; z < SIZE / 2; z++) {
				for (int x = 0; x < SIZE / 2; x++) {
					int a = 0, r = 0, g = 0, b = 0, n = 0;
					for (int d = 0; d < 4; d++) {
						int c = child[(z * 2 + (d >> 1)) * SIZE + x * 2 + (d & 1)];
						int ca = c >>> 24;
						if (ca == 0) continue;
						a += ca;
						r += (c >> 16) & 255;
						g += (c >> 8) & 255;
						b += c & 255;
						n++;
					}
					if (n > 0) {
						out[(oz + z) * SIZE + ox + x] = ((a / 4) << 24) | ((r / n) << 16) | ((g / n) << 8) | (b / n);
					}
				}
			}
		}
		return out;
	}

	static int[] read(Path f) {
		int[] px = new int[SIZE * SIZE];
		if (Files.exists(f)) {
			try {
				BufferedImage img = ImageIO.read(f.toFile());
				if (img != null && img.getWidth() == SIZE && img.getHeight() == SIZE) {
					img.getRGB(0, 0, SIZE, SIZE, px, 0, SIZE);
				}
			} catch (IOException e) {
				Atlas.LOGGER.warn("Unreadable tile {}: {}", f, e.toString());
			}
		}
		return px;
	}

	static void write(Path f, int[] px) {
		try {
			Files.createDirectories(f.getParent());
			BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
			img.setRGB(0, 0, SIZE, SIZE, px, 0, SIZE);
			Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
			ImageIO.write(img, "png", tmp.toFile());
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Atlas.LOGGER.warn("Could not write tile {}: {}", f, e.toString());
		}
	}

	/** Every level-0 tile that exists on disk (for the web page's initial view). */
	public List<int[]> baseTiles() {
		List<int[]> out = new ArrayList<>();
		Path d = this.dir.resolve("0");
		if (!Files.isDirectory(d)) return out;
		try (var s = Files.list(d)) {
			s.forEach(p -> {
				String n = p.getFileName().toString();
				if (n.endsWith(".png")) {
					String[] xz = n.substring(0, n.length() - 4).split("_");
					try {
						out.add(new int[]{Integer.parseInt(xz[0]), Integer.parseInt(xz[1])});
					} catch (NumberFormatException ignored) {
					}
				}
			});
		} catch (IOException ignored) {
		}
		return out;
	}
}
