package dev.klear.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * POST /place_at. Body: {"item": "minecraft:oak_door", "x": 10, "y": 64, "z": -3}.
 *
 * <p>Places the named block at the exact target coordinate, using whatever's below
 * as the support (places on its top face). The agent owns positioning — we don't
 * move them; we just verify they can reach the target's support from where they
 * stand and send the place packet. Unlike {@code /place}, no candidate search and
 * no anti-casing ring-1 check.
 *
 * <p>For doors and other multi-cell items, only the bottom cell is specified;
 * MC auto-fills the upper half. The cell above the target must be air or the
 * place will fail with internal_error at verify time.
 */
public final class PlaceAtHandler implements HttpHandler {
	private static final int MAX_BODY_BYTES = 4096;

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		try {
			if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				respond(exchange, 405, Map.of("error", "method not allowed"));
				return;
			}

			Body body;
			try {
				body = parseBody(exchange);
			} catch (IllegalArgumentException e) {
				respond(exchange, 400, Map.of("error", "bad request: " + e.getMessage()));
				return;
			}

			Placer.Result result;
			try {
				result = Placer.placeAt(body.item, new BlockPos(body.x, body.y, body.z));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				respond(exchange, 500, Map.of("error", "interrupted"));
				return;
			} catch (RuntimeException e) {
				HomunculusClient.LOGGER.error("place_at failed", e);
				respond(exchange, 200, failureBody("internal_error", "place threw: " + rootMessage(e)));
				return;
			}

			if (result instanceof Placer.Ok ok) {
				Map<String, Object> resp = new LinkedHashMap<>();
				resp.put("success", true);
				resp.put("placed_at", List.of(ok.x(), ok.y(), ok.z()));
				respond(exchange, 200, resp);
			} else if (result instanceof Placer.Failure f) {
				respond(exchange, 200, failureBody(f.reason(), f.message()));
			}
		} finally {
			exchange.close();
		}
	}

	private record Body(String item, int x, int y, int z) {}

	private static Body parseBody(HttpExchange exchange) throws IOException {
		byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
		if (bytes.length > MAX_BODY_BYTES) throw new IllegalArgumentException("body too large");
		String raw = new String(bytes, StandardCharsets.UTF_8).trim();
		if (raw.isEmpty()) throw new IllegalArgumentException("empty body");
		Object parsed;
		try {
			parsed = Json.parse(raw);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("invalid JSON (" + e.getMessage() + ")");
		}
		if (!(parsed instanceof Map<?, ?> m)) throw new IllegalArgumentException("expected JSON object");
		Object itemRaw = m.get("item");
		if (!(itemRaw instanceof String item) || item.isEmpty()) {
			throw new IllegalArgumentException("'item' must be a non-empty string");
		}
		return new Body(item, requireInt(m, "x"), requireInt(m, "y"), requireInt(m, "z"));
	}

	private static int requireInt(Map<?, ?> m, String key) {
		Object v = m.get(key);
		if (v instanceof Number n) return n.intValue();
		throw new IllegalArgumentException("'" + key + "' must be an integer");
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
