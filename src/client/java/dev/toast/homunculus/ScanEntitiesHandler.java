package dev.toast.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ScanEntitiesHandler implements HttpHandler {
	private static final long SNAPSHOT_TIMEOUT_MS = 2000;
	private static final int DEFAULT_RADIUS = 32;
	private static final int MAX_RADIUS = 64;
	private static final int DEFAULT_LIMIT = 5;

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		try {
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				respond(exchange, 405, failure("bad_request", "method not allowed"));
				return;
			}

			Map<String, String> q;
			try {
				q = parseQuery(exchange.getRequestURI());
			} catch (IllegalArgumentException e) {
				respond(exchange, 400, failure("bad_request", "bad query: " + e.getMessage()));
				return;
			}

			String typeStr = q.get("type");
			if (typeStr == null || typeStr.isEmpty()) {
				respond(exchange, 400, failure("bad_request", "type query param is required"));
				return;
			}

			final int radius;
			final int limit;
			try {
				int r = q.containsKey("radius") ? Integer.parseInt(q.get("radius")) : DEFAULT_RADIUS;
				if (r < 0) r = 0;
				radius = Math.min(r, MAX_RADIUS);
				int l = q.containsKey("limit") ? Integer.parseInt(q.get("limit")) : DEFAULT_LIMIT;
				limit = Math.max(0, l);
			} catch (NumberFormatException e) {
				respond(exchange, 400, failure("bad_request", "radius and limit must be integers"));
				return;
			}

			Map<String, Object> body;
			try {
				body = ClientThread.supply(() -> scan(typeStr, radius, limit))
						.get(SNAPSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				respond(exchange, 504, failure("internal_error", "client thread timeout"));
				return;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				respond(exchange, 500, failure("internal_error", "interrupted"));
				return;
			} catch (ExecutionException e) {
				HomunculusClient.LOGGER.error("scan_entities failed", e.getCause());
				respond(exchange, 500, failure("internal_error", "internal error"));
				return;
			}

			int status = 200;
			if (Boolean.FALSE.equals(body.get("success"))) {
				Object r = body.get("reason");
				if ("unknown_entity_type".equals(r)) status = 400;
				else status = 503;
			}
			respond(exchange, status, body);
		} finally {
			exchange.close();
		}
	}

	/** Runs on the client/render thread — the client entity list is mutated there. */
	private static Map<String, Object> scan(String typeStr, int radius, int limit) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (p == null || level == null) {
			return failure("internal_error", "no player (not in world)");
		}

		Optional<EntityType<?>> resolved = EntityType.byString(typeStr);
		if (resolved.isEmpty()) {
			return failure("unknown_entity_type",
					"entity type '" + typeStr + "' is not registered");
		}
		EntityType<?> target = resolved.get();

		// Nearest-first via the shared entity-resolution highway, then snapshot to the
		// long-standing /scan_entities record shape and apply the result cap.
		List<Entity> matched = Entities.query(p, level, radius, e -> e.getType() == target);

		List<Map<String, Object>> records = new ArrayList<>(matched.size());
		for (Entity e : matched) {
			if (records.size() >= limit) break;
			records.add(Entities.snapshot(p, e).toJson());
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("entities", records);
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
