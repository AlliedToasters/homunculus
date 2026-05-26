package dev.toast.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET  /food_policy            -> {"success": true, "mode": "any"|"cooked_only"}
 * POST /food_policy  {"mode": "any"|"cooked_only"}
 *
 * Reads/sets the {@link FoodPolicy} mode that governs which foods the
 * offhand-food curator stages for AutoEat. Pure in-memory state — no client
 * thread needed (the curator reads it on its own client-thread pass via /equip).
 */
public final class FoodPolicyHandler implements HttpHandler {
	private static final int MAX_BODY_BYTES = 1024;

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		try {
			String method = exchange.getRequestMethod();
			if ("GET".equalsIgnoreCase(method)) {
				respond(exchange, 200, ok(FoodPolicy.get()));
			} else if ("POST".equalsIgnoreCase(method)) {
				handlePost(exchange);
			} else {
				respond(exchange, 405, failure("bad_request", "method not allowed"));
			}
		} finally {
			exchange.close();
		}
	}

	private void handlePost(HttpExchange exchange) throws IOException {
		byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
		if (bytes.length > MAX_BODY_BYTES) {
			respond(exchange, 400, failure("bad_request", "body too large"));
			return;
		}
		String body = new String(bytes, StandardCharsets.UTF_8).trim();
		if (body.isEmpty()) {
			respond(exchange, 400, failure("bad_request", "empty body"));
			return;
		}
		Object parsed;
		try {
			parsed = Json.parse(body);
		} catch (IllegalArgumentException e) {
			respond(exchange, 400, failure("bad_request", "invalid JSON (" + e.getMessage() + ")"));
			return;
		}
		if (!(parsed instanceof Map<?, ?> m)) {
			respond(exchange, 400, failure("bad_request", "expected JSON object"));
			return;
		}
		Object modeRaw = m.get("mode");
		if (!(modeRaw instanceof String s)) {
			respond(exchange, 400, failure("bad_request", "'mode' must be a string"));
			return;
		}
		FoodPolicy.Mode parsedMode = FoodPolicy.parse(s);
		if (parsedMode == null) {
			respond(exchange, 400, failure("bad_request",
					"unknown mode '" + s + "' (expected 'any' or 'cooked_only')"));
			return;
		}
		FoodPolicy.set(parsedMode);
		respond(exchange, 200, ok(parsedMode));
	}

	private static Map<String, Object> ok(FoodPolicy.Mode mode) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("success", true);
		m.put("mode", FoodPolicy.label(mode));
		return m;
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
