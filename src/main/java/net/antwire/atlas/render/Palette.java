package net.antwire.atlas.render;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

/** Map colours, a little calmer than vanilla's: softer greens, water by depth, everything slightly desaturated. */
final class Palette {
	private Palette() {
	}

	static int color(BlockState state, MapColor color) {
		int rgb = color.col;
		if (color == MapColor.GRASS) rgb = 0x6E9F48;
		else if (color == MapColor.PLANT) rgb = 0x3F7A34;
		else if (color == MapColor.COLOR_GREEN) rgb = 0x5C7A2E;
		return desaturate(rgb, 0.12);
	}

	/** Water: light and greenish at the shore, deep blue further out; a faint checker pattern for texture. */
	static int water(int depth, int x, int z) {
		double t = Math.min(1, depth / 14.0);
		int r = (int) (78 - 46 * t), g = (int) (138 - 66 * t), b = (int) (178 - 42 * t);
		if (((x + z) & 1) == 0 && depth > 1) {
			r += 4;
			g += 4;
			b += 5;
		}
		return (r << 16) | (g << 8) | b;
	}

	static int shade(int rgb, double f) {
		int r = (int) Math.clamp(((rgb >> 16) & 255) * f, 0, 255);
		int g = (int) Math.clamp(((rgb >> 8) & 255) * f, 0, 255);
		int b = (int) Math.clamp((rgb & 255) * f, 0, 255);
		return (r << 16) | (g << 8) | b;
	}

	private static int desaturate(int rgb, double amount) {
		int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
		int grey = (r * 30 + g * 59 + b * 11) / 100;
		r = (int) (r + (grey - r) * amount);
		g = (int) (g + (grey - g) * amount);
		b = (int) (b + (grey - b) * amount);
		return (r << 16) | (g << 8) | b;
	}
}
