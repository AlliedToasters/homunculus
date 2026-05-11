package dev.klear.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * POST /equip. No body. Auto-equips best tool/food/building/armor across the
 * player's inventory; see {@link Equipper} for slot scheme and ranking.
 */
public final class EquipHandler implements HttpHandler {

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		try {
			if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				respond(exchange, 405, Map.of("error", "method not allowed"));
				return;
			}

			Equipper.Result result;
			try {
				result = Equipper.equipAll();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				respond(exchange, 500, Map.of("error", "interrupted"));
				return;
			} catch (RuntimeException e) {
				HomunculusClient.LOGGER.error("equip failed", e);
				respond(exchange, 200, failureBody("internal_error", "equip threw: " + rootMessage(e)));
				return;
			}

			if (result instanceof Equipper.Ok ok) {
				Map<String, Object> body = new LinkedHashMap<>();
				body.put("success", true);
				body.put("equipped", ok.equipped());
				List<Object> changes = new ArrayList<>();
				for (Equipper.Change c : ok.changes()) {
					Map<String, Object> entry = new LinkedHashMap<>();
					entry.put("role", c.role());
					entry.put("from", c.from());
					entry.put("to", c.to());
					changes.add(entry);
				}
				body.put("changes", changes);
				body.put("message", ok.message());
				respond(exchange, 200, body);
			} else if (result instanceof Equipper.Failure f) {
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
