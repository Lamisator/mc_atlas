package net.antwire.atlas.render;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * The top view of a chunk, like a Minecraft map but finer: the colour of the highest visible block, water darker the
 * deeper it is, and the land shaded by its slope (lighter where it rises towards the south, darker where it falls).
 * Works on a loaded chunk or on the sections of a saved one (so a full render never has to load chunks).
 */
public final class ChunkColors {
	/** Columns of one chunk, x and z from 0 to 15. */
	public interface Blocks {
		BlockState get(int x, int y, int z);

		/** Highest non-air block (or a lower bound to start looking from). */
		int top(int x, int z);

		int minY();
	}

	/** Colours (ARGB, row by row from the north-west) and heights of a chunk. */
	public record Result(int[] colors, int[] heights) {
		/** The heights of the southernmost row, for the relief of the chunk south of this one. */
		public int[] southRow() {
			int[] r = new int[16];
			System.arraycopy(this.heights, 240, r, 0, 16);
			return r;
		}
	}

	private ChunkColors() {
	}

	public static Blocks of(LevelChunk chunk, boolean ceiling) {
		int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		return new Blocks() {
			public BlockState get(int x, int y, int z) {
				return chunk.getBlockState(p.set(bx + x, y, bz + z));
			}

			public int top(int x, int z) {
				return ceiling ? Math.min(chunk.getMaxY(), 120) : chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
			}

			public int minY() {
				return chunk.getMinY();
			}
		};
	}

	/** Saved sections, the lowest at index 0 (section y = minSectionY + index). */
	public static Blocks of(LevelChunkSection[] sections, int minSectionY, boolean ceiling) {
		int minY = minSectionY * 16;
		int topSection = -1;
		for (int i = sections.length - 1; i >= 0; i--) {
			if (sections[i] != null && !sections[i].hasOnlyAir()) {
				topSection = i;
				break;
			}
		}
		int top = topSection < 0 ? minY : minY + topSection * 16 + 15;
		return new Blocks() {
			public BlockState get(int x, int y, int z) {
				int i = (y - minY) >> 4;
				if (i < 0 || i >= sections.length || sections[i] == null) return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
				return sections[i].getBlockState(x, y & 15, z);
			}

			public int top(int x, int z) {
				return ceiling ? Math.min(top, 120) : top;
			}

			public int minY() {
				return minY;
			}
		};
	}

	/**
	 * @param north heights of the row just north of this chunk, or null (no relief on the first row)
	 * @param ceiling a dimension with a roof (the Nether): show the floor under the first open space below the roof
	 */
	public static Result render(Blocks blocks, int[] north, boolean ceiling) {
		int[] out = new int[256];
		int[] heights = new int[256];
		boolean[] water = new boolean[256];
		int minY = blocks.minY();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int y = blocks.top(x, z);
				if (ceiling) {
					boolean air = false;
					for (; y > minY; y--) {
						boolean empty = blocks.get(x, y, z).isAir();
						if (empty) air = true;
						else if (air) break;
					}
				}
				MapColor color = MapColor.NONE;
				BlockState state = null;
				for (; y >= minY; y--) {
					state = blocks.get(x, y, z);
					if (state.isAir()) continue;
					color = state.getMapColor(EmptyBlockGetter.INSTANCE, p.set(x, y, z));
					if (color != MapColor.NONE) break;
				}
				int i = z * 16 + x;
				if (color == MapColor.NONE || y < minY) {
					heights[i] = minY;
					continue;
				}
				heights[i] = y;
				if (state.getFluidState().is(FluidTags.WATER)) {
					water[i] = true;
					int depth = 0;
					int wy = y;
					while (wy > minY && depth < 24 && blocks.get(x, wy - 1, z).getFluidState().is(FluidTags.WATER)) {
						wy--;
						depth++;
					}
					out[i] = Palette.water(depth, x, z);
				} else {
					out[i] = Palette.color(state, color);
				}
			}
		}
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int i = z * 16 + x;
				if (heights[i] == minY && out[i] == 0) continue;
				int h = heights[i];
				int hn = z > 0 ? heights[i - 16] : north != null ? north[x] : h;
				double light = water[i] ? 1 : 1 + Math.clamp((h - hn) * 0.07, -0.3, 0.25);
				out[i] = 0xFF000000 | Palette.shade(out[i], light);
			}
		}
		return new Result(out, heights);
	}
}
