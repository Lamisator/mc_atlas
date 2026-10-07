package net.antwire.atlas.layers;

import com.google.gson.stream.JsonWriter;
import net.antwire.atlas.render.Renderer;
import net.antwire.gridworks.grid.ConductorType;
import net.antwire.gridworks.grid.GridData;
import net.antwire.gridworks.grid.GridEntry;
import net.antwire.gridworks.grid.GridManager;
import net.antwire.gridworks.grid.VoltageClass;
import net.antwire.gridworks.grid.Wire;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Gridworks power grids (only loaded when Gridworks is installed): cables coloured by voltage with their load, overhead
 * lines with their current, machines, meters, breakers and connectors, and a summary of every network.
 */
final class GridLayer {
	/** Block names of machines, remembered from when their chunk was loaded. */
	private static final Map<Long, String> NAMES = new HashMap<>();

	private GridLayer() {
	}

	static void update(ServerLevel level) throws IOException {
		GridManager gm = GridManager.get();
		if (gm == null) return;
		GridData.Dim dim = gm.data().dim(GridManager.dimKey(level));
		StringWriter out = new StringWriter();
		JsonWriter w = new JsonWriter(out);
		w.beginObject();
		Map<GridManager.Network, Integer> networks = new IdentityHashMap<>();
		w.name("cables").beginArray();
		for (Map.Entry<Long, GridEntry> e : dim.entries.entrySet()) {
			GridEntry g = e.getValue();
			if (g.kind != GridEntry.Kind.CABLE || g.conductor < 0) continue;
			BlockPos p = BlockPos.of(e.getKey());
			ConductorType t = ConductorType.values()[g.conductor];
			GridManager.Network n = gm.network(level, p, t.voltage());
			double amps = n == null ? 0 : n.amps.getOrDefault(e.getKey(), 0.0);
			w.beginArray().value(p.getX()).value(p.getY()).value(p.getZ()).value(t.voltage().ordinal())
					.value(round(g.ratingAmps > 0 ? amps / g.ratingAmps : 0)).value(net(networks, n)).endArray();
		}
		w.endArray();
		w.name("wires").beginArray();
		for (Wire wire : dim.wires()) {
			BlockPos a = wire.posA(), b = wire.posB();
			ConductorType t = wire.conductor();
			double amps = gm.wireAmps(level, wire);
			w.beginArray().value(a.getX()).value(a.getZ()).value(b.getX()).value(b.getZ()).value(t.voltage().ordinal())
					.value(round(amps / Math.max(1e-9, t.amps()))).value(round(amps)).value(net(networks, gm.network(level, a, t.voltage()))).endArray();
		}
		w.endArray();
		w.name("nodes").beginArray();
		for (Map.Entry<Long, GridEntry> e : dim.entries.entrySet()) {
			GridEntry g = e.getValue();
			if (g.kind == GridEntry.Kind.CABLE) continue;
			BlockPos p = BlockPos.of(e.getKey());
			if (level.isLoaded(p)) {
				NAMES.put(e.getKey(), BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).getPath());
			}
			String name = NAMES.getOrDefault(e.getKey(), g.kind.name().toLowerCase(Locale.ROOT));
			int cls = -1;
			for (VoltageClass v : g.classes()) cls = Math.max(cls, v.ordinal());
			w.beginArray().value(p.getX()).value(p.getY()).value(p.getZ()).value(g.kind.name().toLowerCase(Locale.ROOT)).value(name)
					.value(cls).value(g.closed).endArray();
		}
		w.endArray();
		w.name("networks").beginArray();
		networks.entrySet().stream().sorted(Map.Entry.comparingByValue()).forEach(e -> {
			GridManager.Network n = e.getKey();
			try {
				w.beginObject().name("id").value(e.getValue()).name("voltage").value(n.voltage.ordinal()).name("volts").value(round(n.volts))
						.name("offered").value(Math.round(n.offered)).name("demanded").value(Math.round(n.demanded))
						.name("delivered").value(Math.round(n.delivered)).name("fraction").value(round(n.fraction))
						.name("storedWh").value(Math.round(n.storedWh)).name("capacityWh").value(Math.round(n.capacityWh))
						.name("devices").value(n.devices).endObject();
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		});
		w.endArray();
		w.name("classes").beginArray();
		for (VoltageClass v : VoltageClass.values()) {
			w.beginObject().name("id").value(v.id()).name("volts").value(v.volts()).name("color").value(String.format("#%06x", v.color() & 0xFFFFFF)).endObject();
		}
		w.endArray();
		w.endObject();
		w.close();
		LayerData.put("grid/" + Renderer.key(level), out.toString());
	}

	private static int net(Map<GridManager.Network, Integer> ids, GridManager.Network n) {
		if (n == null) return -1;
		return ids.computeIfAbsent(n, k -> ids.size());
	}

	private static double round(double v) {
		return Math.round(v * 1000) / 1000.0;
	}
}
