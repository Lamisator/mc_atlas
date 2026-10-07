package net.antwire.atlas;

import net.antwire.atlas.command.AtlasCommands;
import net.antwire.atlas.layers.LayerData;
import net.antwire.atlas.layers.Layers;
import net.antwire.atlas.layers.Transmitters;
import net.antwire.atlas.layers.Waypoints;
import net.antwire.atlas.render.Renderer;
import net.antwire.atlas.web.WebServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Atlas: a live web map of the world, like Dynmap. Server side only - players need nothing installed. The map is
 * rendered into tiles as chunks are loaded and changed; an embedded web server shows it with layers for waypoints,
 * players, Gridworks power grids, Radiation (and Fission's clouds) and Ham Radio broadcast reception.
 */
public class Atlas implements ModInitializer {
	public static final String MOD_ID = "atlas";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static String version() {
		return FabricLoader.getInstance().getModContainer(MOD_ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
	}

	@Override
	public void onInitialize() {
		AtlasConfig.load();
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			Transmitters.load(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("atlas"));
			Renderer.start(server);
			Waypoints.load(Renderer.root());
			Layers.reset();
			WebServer.start(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			WebServer.stop();
			Renderer.stop();
			Transmitters.save();
			LayerData.clear();
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			Renderer.tick(server);
			Layers.tick(server);
		});
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> Renderer.chunkLoaded(level, chunk));
		ServerChunkEvents.CHUNK_UNLOAD.register(Renderer::chunkUnloaded);
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> AtlasCommands.register(dispatcher));
		LOGGER.info("Atlas {}: layers {}", version(), Layers.available());
	}
}
