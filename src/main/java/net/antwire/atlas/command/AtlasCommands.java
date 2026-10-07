package net.antwire.atlas.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.antwire.atlas.AtlasConfig;
import net.antwire.atlas.layers.Layers;
import net.antwire.atlas.layers.Waypoints;
import net.antwire.atlas.render.Renderer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.phys.Vec3;

import java.net.URI;
import java.util.Locale;

/**
 * {@code /atlas} - the address and state of the map; {@code /atlas waypoint add|remove|list}; operators:
 * {@code /atlas render [here <radius>]} and {@code /atlas refresh}.
 */
public final class AtlasCommands {
	private AtlasCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("atlas")
				.executes(AtlasCommands::status)
				.then(Commands.literal("waypoint")
						.then(Commands.literal("add").then(Commands.argument("name", StringArgumentType.greedyString()).executes(AtlasCommands::add)))
						.then(Commands.literal("remove").then(Commands.argument("name", StringArgumentType.greedyString()).executes(AtlasCommands::remove)))
						.then(Commands.literal("list").executes(AtlasCommands::list)))
				.then(Commands.literal("render").requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
						.executes(ctx -> {
							int n = 0;
							for (var d : Renderer.dims().values()) n += Renderer.full(d);
							int total = n;
							ctx.getSource().sendSuccess(() -> Component.literal("Rendering all " + total + " saved chunks again"), true);
							return total;
						})
						.then(Commands.literal("here").then(Commands.argument("radius", IntegerArgumentType.integer(0, 32)).executes(ctx -> {
							int n = Renderer.around(ctx.getSource().getLevel(), net.minecraft.world.level.ChunkPos.containing(
									net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition())), IntegerArgumentType.getInteger(ctx, "radius"));
							ctx.getSource().sendSuccess(() -> Component.literal("Redrew " + n + " loaded chunks"), false);
							return n;
						}))))
				.then(Commands.literal("refresh").requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
						.executes(ctx -> {
							Layers.refreshSoon();
							ctx.getSource().sendSuccess(() -> Component.literal("Dose rates and broadcast coverage are measured again"), false);
							return 1;
						})));
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		AtlasConfig c = AtlasConfig.get();
		String url = "http://" + (c.bind.equals("0.0.0.0") ? "<this server>" : c.bind) + ":" + c.port + "/";
		ctx.getSource().sendSuccess(() -> Component.literal("Atlas web map: " + url + " (layers " + Layers.available() + ")"), false);
		ctx.getSource().sendSuccess(() -> Component.literal("  broadcast coverage: " + Layers.coverageState()), false);
		for (var d : Renderer.dims().values()) {
			String line = d.fullRunning()
					? String.format(Locale.ROOT, "  %s: rendering, %d of %d saved chunks", d.key, d.fullDone.get(), d.fullTotal)
					: String.format(Locale.ROOT, "  %s: up to date (%d tiles waiting to be written)", d.key, d.tiles.pending());
			ctx.getSource().sendSuccess(() -> Component.literal(line), false);
		}
		return 1;
	}

	private static int add(CommandContext<CommandSourceStack> ctx) {
		String name = StringArgumentType.getString(ctx, "name").trim();
		if (name.isEmpty() || name.length() > 40) {
			ctx.getSource().sendFailure(Component.literal("Waypoint names have 1 to 40 characters"));
			return 0;
		}
		Vec3 p = ctx.getSource().getPosition();
		Waypoints.Waypoint w = new Waypoints.Waypoint();
		w.name = name;
		w.dim = Renderer.key(ctx.getSource().getLevel());
		w.x = (int) Math.floor(p.x);
		w.y = (int) Math.floor(p.y);
		w.z = (int) Math.floor(p.z);
		w.by = ctx.getSource().getTextName();
		Waypoints.add(w);
		ctx.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "Waypoint '%s' at %d %d %d", w.name, w.x, w.y, w.z)), true);
		return 1;
	}

	private static int remove(CommandContext<CommandSourceStack> ctx) {
		String name = StringArgumentType.getString(ctx, "name").trim();
		if (!Waypoints.remove(name)) {
			ctx.getSource().sendFailure(Component.literal("No waypoint '" + name + "'"));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.literal("Waypoint '" + name + "' removed"), true);
		return 1;
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		var all = Waypoints.all();
		if (all.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.literal("No waypoints yet: /atlas waypoint add <name>"), false);
		}
		for (Waypoints.Waypoint w : all) {
			Component line = Component.literal(String.format(Locale.ROOT, " %s (%s) %d %d %d", w.name, w.dim, w.x, w.y, w.z))
					.withStyle(Style.EMPTY.withClickEvent(new ClickEvent.SuggestCommand("/tp @s " + w.x + " " + w.y + " " + w.z)));
			ctx.getSource().sendSuccess(() -> line, false);
		}
		return all.size();
	}
}
