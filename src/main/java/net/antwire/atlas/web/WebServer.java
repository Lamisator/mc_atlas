package net.antwire.atlas.web;

import com.google.gson.stream.JsonWriter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.antwire.atlas.Atlas;
import net.antwire.atlas.AtlasConfig;
import net.antwire.atlas.layers.LayerData;
import net.antwire.atlas.layers.Layers;
import net.antwire.atlas.render.Renderer;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The web map, read only: the page and its scripts from the mod jar, tiles from {@code <world>/atlas/tiles}, layers as
 * JSON. Nothing a visitor sends changes the world.
 */
public final class WebServer {
	private static final Pattern TILE = Pattern.compile("^/tiles/([a-z0-9_.-]+)/(\\d)/(-?\\d+)_(-?\\d+)\\.png$");
	private static final Pattern LAYER = Pattern.compile("^/api/(grid|radiation|doserate|stations|coverage)/([a-z0-9_.-]+)$");
	private static final Map<String, String> TYPES = Map.of("html", "text/html; charset=utf-8", "js", "text/javascript; charset=utf-8",
			"css", "text/css; charset=utf-8", "png", "image/png", "svg", "image/svg+xml", "json", "application/json; charset=utf-8");
	private static final Pattern MEASURE = Pattern.compile("^/api/measure/([a-z0-9_.-]+)$");
	private static HttpServer server;
	private static MinecraftServer minecraft;
	private static volatile String info = "{}";

	private WebServer() {
	}

	public static void start(MinecraftServer mc) {
		AtlasConfig config = AtlasConfig.get();
		info = info(mc);
		minecraft = mc;
		try {
			server = HttpServer.create(new InetSocketAddress(config.bind, config.port), 64);
		} catch (IOException e) {
			Atlas.LOGGER.error("Atlas: cannot open the web map on {}:{} ({}) - is the port in use?", config.bind, config.port, e.toString());
			return;
		}
		server.setExecutor(Executors.newFixedThreadPool(6, r -> {
			Thread t = new Thread(r, "Atlas web");
			t.setDaemon(true);
			return t;
		}));
		server.createContext("/", WebServer::handle);
		server.start();
		Atlas.LOGGER.info("Atlas: web map on http://{}:{}/", config.bind, config.port);
	}

	public static void stop() {
		if (server != null) {
			server.stop(0);
			server = null;
		}
		minecraft = null;
	}

	private static void handle(HttpExchange ex) throws IOException {
		try (ex) {
			if (!ex.getRequestMethod().equals("GET") && !ex.getRequestMethod().equals("HEAD")) {
				send(ex, 405, "text/plain", "GET only".getBytes(StandardCharsets.UTF_8), false);
				return;
			}
			String path = ex.getRequestURI().getPath();
			Matcher m = TILE.matcher(path);
			if (m.matches()) {
				Path root = Renderer.root();
				Path f = root == null ? null : root.resolve("tiles").resolve(m.group(1)).resolve(m.group(2)).resolve(m.group(3) + "_" + m.group(4) + ".png");
				if (f == null || !Files.isRegularFile(f)) {
					send(ex, 404, "text/plain", new byte[0], false);
				} else {
					ex.getResponseHeaders().set("Cache-Control", "no-cache");
					send(ex, 200, "image/png", Files.readAllBytes(f), false);
				}
				return;
			}
			m = LAYER.matcher(path);
			if (m.matches()) {
				json(ex, LayerData.get(m.group(1) + "/" + m.group(2)));
				return;
			}
			m = MEASURE.matcher(path);
			if (m.matches()) {
				measure(ex, m.group(1));
				return;
			}
			switch (path) {
				case "/api/info" -> json(ex, info);
				case "/api/live" -> json(ex, LayerData.get("live"));
				case "/api/waypoints" -> json(ex, LayerData.get("waypoints"));
				default -> resource(ex, path.equals("/") ? "/index.html" : path);
			}
		} catch (IOException e) {
			// the browser went away
		}
	}

	/** {@code /api/measure/<dim>?x=..&z=..[&y=..]}: the dose rate there, measured now. */
	private static void measure(HttpExchange ex, String dim) throws IOException {
		Map<String, String> q = new java.util.HashMap<>();
		String query = ex.getRequestURI().getRawQuery();
		if (query != null) {
			for (String kv : query.split("&")) {
				int i = kv.indexOf('=');
				if (i > 0) q.put(kv.substring(0, i), kv.substring(i + 1));
			}
		}
		try {
			int x = Integer.parseInt(q.get("x")), z = Integer.parseInt(q.get("z"));
			Integer y = q.containsKey("y") ? Integer.valueOf(q.get("y")) : null;
			if (Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000) throw new NumberFormatException();
			MinecraftServer mc = minecraft;
			String body = mc == null ? null : net.antwire.atlas.layers.Layers.measure(mc, dim, x, z, y);
			if (body == null) {
				send(ex, 404, "text/plain", "no radiation here".getBytes(StandardCharsets.UTF_8), true);
			} else {
				json(ex, body);
			}
		} catch (NumberFormatException | NullPointerException e) {
			send(ex, 400, "text/plain", "x and z needed".getBytes(StandardCharsets.UTF_8), true);
		}
	}

	private static void resource(HttpExchange ex, String path) throws IOException {
		if (path.contains("..") || !path.matches("^/[a-zA-Z0-9_./-]+$")) {
			send(ex, 404, "text/plain", new byte[0], false);
			return;
		}
		try (InputStream in = WebServer.class.getResourceAsStream("/web" + path)) {
			if (in == null) {
				send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8), false);
				return;
			}
			byte[] data = in.readAllBytes();
			String ext = path.substring(path.lastIndexOf('.') + 1);
			if (path.equals("/index.html")) {
				data = new String(data, StandardCharsets.UTF_8).replace("{{title}}", escape(AtlasConfig.get().title)).getBytes(StandardCharsets.UTF_8);
			}
			ex.getResponseHeaders().set("Cache-Control", "max-age=300");
			send(ex, 200, TYPES.getOrDefault(ext, "application/octet-stream"), data, false);
		}
	}

	private static void json(HttpExchange ex, String body) throws IOException {
		ex.getResponseHeaders().set("Cache-Control", "no-store");
		send(ex, 200, TYPES.get("json"), body.getBytes(StandardCharsets.UTF_8), true);
	}

	private static void send(HttpExchange ex, int code, String type, byte[] body, boolean cors) throws IOException {
		ex.getResponseHeaders().set("Content-Type", type);
		if (cors) ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
		boolean head = ex.getRequestMethod().equals("HEAD");
		ex.sendResponseHeaders(code, head || body.length == 0 ? -1 : body.length);
		if (!head && body.length > 0) {
			try (OutputStream out = ex.getResponseBody()) {
				out.write(body);
			}
		}
	}

	private static String escape(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	/** Title, dimensions with their spawn and the tiles they have, and which layers this server can show. */
	private static String info(MinecraftServer mc) {
		try {
			StringWriter out = new StringWriter();
			JsonWriter w = new JsonWriter(out);
			w.beginObject().name("title").value(AtlasConfig.get().title).name("version").value(Atlas.version());
			w.name("layers").beginArray();
			for (String l : Layers.available()) w.value(l);
			w.endArray();
			w.name("dims").beginArray();
			List<Renderer.Dim> dims = new java.util.ArrayList<>(Renderer.dims().values());
			// Overworld first (the page opens on the first), then the others in the config's order
			List<String> order = AtlasConfig.get().dimensions;
			dims.sort(java.util.Comparator.comparingInt((Renderer.Dim d) -> d.level == mc.overworld() ? -1 : order.indexOf(d.level.dimension().identifier().toString())));
			for (var d : dims) {
				var spawn = mc.overworld().getRespawnData().pos();
				boolean over = d.level == mc.overworld();
				w.beginObject().name("key").value(d.key).name("id").value(d.level.dimension().identifier().toString())
						.name("x").value(over ? spawn.getX() : 0).name("z").value(over ? spawn.getZ() : 0).endObject();
			}
			w.endArray().endObject();
			w.close();
			return out.toString();
		} catch (IOException e) {
			return "{}";
		}
	}

	public static List<String> addresses() {
		AtlasConfig c = AtlasConfig.get();
		return List.of("http://" + (c.bind.equals("0.0.0.0") ? "<server>" : c.bind) + ":" + c.port + "/");
	}
}
