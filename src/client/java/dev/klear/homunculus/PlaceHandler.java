package dev.klear.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * POST /place. Body: {"item": "minecraft:crafting_table"}. Places the named block at whatever the
 * player is currently looking at. The agent owns positioning; we just run the place packet.
 */
public final class PlaceHandler implements HttpHandler {
	private static final int MAX_BODY_BYTES = 4096;

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		try {
			if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				respond(exchange, 405, Map.of("error", "method not allowed"));
				return;
			}

			String item;
			try {
				item = parseItem(exchange);
			} catch (IllegalArgumentException e) {
				respond(exchange, 400, Map.of("error", "bad request: " + e.getMessage()));
				return;
			}

			Placer.Result result;
			try {
				result = Placer.place(item);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				respond(exchange, 500, Map.of("error", "interrupted"));
				return;
			} catch (RuntimeException e) {
				HomunculusClient.LOGGER.error("place failed", e);
				respond(exchange, 200, failureBody("internal_error", "place threw: " + rootMessage(e)));
				return;
			}

			if (result instanceof Placer.Ok ok) {
				Map<String, Object> body = new LinkedHashMap<>();
				body.put("success", true);
				body.put("placed_at", List.of(ok.x(), ok.y(), ok.z()));
				respond(exchange, 200, body);
			} else if (result instanceof Placer.Failure f) {
				respond(exchange, 200, failureBody(f.reason(), f.message()));
			}
		} finally {
			exchange.close();
		}
	}

	private static String parseItem(HttpExchange exchange) throws IOException {
		byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
		if (bytes.length > MAX_BODY_BYTES) throw new IllegalArgumentException("body too large");
		String body = new String(bytes, StandardCharsets.UTF_8).trim();
		if (body.isEmpty()) throw new IllegalArgumentException("empty body");
		Object parsed;
		try {
			parsed = Json.parse(body);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("invalid JSON (" + e.getMessage() + ")");
		}
		if (!(parsed instanceof Map<?, ?> m)) throw new IllegalArgumentException("expected JSON object");
		Object itemRaw = m.get("item");
		if (!(itemRaw instanceof String item) || item.isEmpty()) {
			throw new IllegalArgumentException("'item' must be a non-empty string");
		}
		return item;
	}

	private static Map<String, Object> failureBody(String reason, String message) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("success", false);
		body.put("reason", reason);
		body.put("message", message);
		return body;
	}

	private static String rootMessage(Throwable t) {
		Throwable c = t.getCause() != null ? t.getCause() : t;
		String m = c.getMessage();
		return m == null ? c.getClass().getSimpleName() : m;
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
