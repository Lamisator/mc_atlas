package net.antwire.atlas.layers;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/** Ground height: real blocks where the chunk is loaded, otherwise the world generator's estimate. */
final class Terrain {
	private Terrain() {
	}

	static int surface(ServerLevel level, int x, int z) {
		if (level.hasChunk(x >> 4, z >> 4)) {
			return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		}
		var source = level.getChunkSource();
		return source.getGenerator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, source.randomState());
	}
}
