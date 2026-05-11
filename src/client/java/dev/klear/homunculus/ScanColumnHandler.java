package dev.klear.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ScanColumnHandler implements HttpHandler {
	private static final long SNAPSHOT_TIMEOUT_MS = 2000;

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		try {
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				respond(exchange, 405, failure("internal_error", "method not allowed"));
				return;
			}

			Map<String, String> q;
			try {
				q = parseQuery(exchange.getRequestURI());
			} catch (IllegalArgumentException e) {
				respond(exchange, 400, failure("internal_error", "bad query: " + e.getMessage()));
				return;
			}

			final Integer reqX, reqZ;
			try {
				reqX = q.containsKey("x") ? Integer.valueOf(q.get("x")) : null;
				reqZ = q.containsKey("z") ? Integer.valueOf(q.get("z")) : null;
			} catch (NumberFormatException e) {
				respond(exchange, 400, failure("internal_error", "x and z must be integers"));
				return;
			}

			Map<String, Object> body;
			try {
				body = ClientThread.supply(() -> scan(reqX, reqZ))
						.get(SNAPSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				respond(exchange, 504, failure("internal_error", "client thread timeout"));
				return;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				respond(exchange, 500, failure("internal_error", "interrupted"));
				return;
			} catch (ExecutionException e) {
				HomunculusClient.LOGGER.error("scan_column failed", e.getCause());
				respond(exchange, 500, failure("internal_error", "internal error"));
				return;
			}

			int status = 200;
			if (Boolean.FALSE.equals(body.get("success"))) {
				Object r = body.get("reason");
				if ("out_of_range".equals(r)) status = 400;
				else status = 503;
			}
			respond(exchange, status, body);
		} finally {
			exchange.close();
		}
	}

	/** Runs on the client/render thread. */
	private static Map<String, Object> scan(Integer reqX, Integer reqZ) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (p == null || level == null) {
			return failure("internal_error", "no player (not in world)");
		}

		int x = reqX != null ? reqX : (int) Math.floor(p.getX());
		int z = reqZ != null ? reqZ : (int) Math.floor(p.getZ());

		int chunkX = x >> 4;
		int chunkZ = z >> 4;
		if (!level.hasChunk(chunkX, chunkZ)) {
			return failure("out_of_range",
					"column (" + x + "," + z + ") is not loaded; move closer and retry");
		}

		int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
		// Heightmap returns one above the topmost matching block (predicate: !isAir).
		// Empty-air column → y == getMinY() (no terrain). Overhang to ceiling → y > getMaxY().
		Object surfaceY = (y <= level.getMinY() || y > level.getMaxY()) ? null : Integer.valueOf(y);

		Map<String, Object> result = new LinkedHashMap<>();
		List<Object> col = new ArrayList<>(2);
		col.add(x);
		col.add(z);
		result.put("column", col);
		result.put("surface_y", surfaceY);
		return result;
	}

	private static Map<String, String> parseQuery(URI uri) {
		Map<String, String> out = new LinkedHashMap<>();
		String raw = uri.getRawQuery();
		if (raw == null || raw.isEmpty()) return out;
		for (String pair : raw.split("&")) {
			if (pair.isEmpty()) continue;
			int eq = pair.indexOf('=');
			if (eq < 0) {
				out.put(decode(pair), "");
			} else {
				out.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
			}
		}
		return out;
	}

	private static String decode(String s) {
		return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
	}

	private static Map<String, Object> failure(String reason, String message) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("success", false);
		m.put("reason", reason);
		m.put("message", message);
		return m;
	}

	private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
		byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("content-type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}
}
