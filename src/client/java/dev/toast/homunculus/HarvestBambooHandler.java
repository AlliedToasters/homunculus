package dev.toast.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST /harvest_bamboo  {"radius": 5}  →
 *   {"success": true, "columns_broken": N, "bases_attempted": M, "bases_in_reach": K}
 *
 * Direct bamboo harvest (Baritone can't — bug #4653). Breaks the ground-level
 * BASE of each bamboo column within reach; the column cascades and the stationary
 * player vacuums the drops. `radius` (default 5, clamped 1..8) is the scan cube
 * half-extent; only bases within block-break reach are actually broken. Counting
 * the cane gained is the caller's job (inventory delta), as with the mine ops.
 */
public final class HarvestBambooHandler implements HttpHandler {
	private static final int MAX_BODY_BYTES = 1024;
	private static final int DEFAULT_RADIUS = 5;

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		try {
			if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				respond(exchange, 405, Map.of("error", "method not allowed"));
				return;
			}

			int radius = DEFAULT_RADIUS;
			try {
				byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
				String body = new String(bytes, StandardCharsets.UTF_8).trim();
				if (!body.isEmpty()) {
					Object parsed = Json.parse(body);
					if (parsed instanceof Map<?, ?> m && m.get("radius") instanceof Number n) {
						radius = n.intValue();
					}
				}
			} catch (RuntimeException ignored) {
				// malformed body → fall back to the default radius
			}

			HarvestBamboo.Result result;
			try {
				result = HarvestBamboo.harvest(radius);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				respond(exchange, 500, Map.of("error", "interrupted"));
				return;
			} catch (RuntimeException e) {
				HomunculusClient.LOGGER.error("harvest_bamboo failed", e);
				respond(exchange, 200, failureBody("internal_error", "harvest_bamboo threw: " + rootMessage(e)));
				return;
			}

			if (result instanceof HarvestBamboo.Ok ok) {
				Map<String, Object> body = new LinkedHashMap<>();
				body.put("success", true);
				body.put("columns_broken", ok.columnsBroken());
				body.put("bases_attempted", ok.basesAttempted());
				body.put("bases_in_reach", ok.basesInReach());
				body.put("debug", TickBreaker.INSTANCE.lastDebug);
				respond(exchange, 200, body);
			} else if (result instanceof HarvestBamboo.Failure f) {
				respond(exchange, 200, failureBody(f.reason(), f.message()));
			}
		} finally {
			exchange.close();
		}
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
