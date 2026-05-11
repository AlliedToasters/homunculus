package dev.klear.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DeathsHandler implements HttpHandler {
	@Override
	public void handle(HttpExchange exchange) throws IOException {
		try {
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				respond(exchange, 405, failure("bad_request", "method not allowed"));
				return;
			}

			long since;
			try {
				since = parseSince(exchange.getRequestURI());
			} catch (NumberFormatException e) {
				respond(exchange, 400, failure("bad_request", "since must be an integer"));
				return;
			}

			List<Map<String, Object>> deaths;
			try {
				deaths = DeathTracker.INSTANCE.snapshot(since);
			} catch (RuntimeException e) {
				HomunculusClient.LOGGER.error("deaths snapshot failed", e);
				respond(exchange, 500, failure("internal_error", "internal error"));
				return;
			}

			Map<String, Object> body = new LinkedHashMap<>();
			body.put("deaths", deaths);
			respond(exchange, 200, body);
		} finally {
			exchange.close();
		}
	}

	private static long parseSince(URI uri) {
		String query = uri.getRawQuery();
		if (query == null || query.isEmpty()) return 0L;
		for (String pair : query.split("&")) {
			int eq = pair.indexOf('=');
			String key = eq < 0 ? pair : pair.substring(0, eq);
			String val = eq < 0 ? "" : pair.substring(eq + 1);
			if ("since".equals(key) && !val.isEmpty()) {
				return Long.parseLong(val);
			}
		}
		return 0L;
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
