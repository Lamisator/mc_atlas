package net.antwire.atlas.layers;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Ground height: real blocks where the chunk is loaded, otherwise the world generator's estimate - with Ham Radio
 * installed taken from its terrain height cache (kept in the world save, filled ahead of time off the server thread),
 * since asking the generator costs a fraction of a millisecond per column.
 */
final class Terrain {
	private Terrain() {
	}

	static int surface(ServerLevel level, int x, int z) {
		if (level.hasChunk(x >> 4, z >> 4)) {
			return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		}
		if (Layers.BROADCAST) {
			return Cached.height(level, x, z);
		}
		var source = level.getChunkSource();
		return source.getGenerator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, source.randomState());
	}

	/** Separate class, so that Ham Radio's classes are only looked up when it is installed. */
	private static final class Cached {
		static int height(ServerLevel level, int x, int z) {
			return dev.hamradio.propagation.HeightCache.height(level, x, z);
		}
	}
}
