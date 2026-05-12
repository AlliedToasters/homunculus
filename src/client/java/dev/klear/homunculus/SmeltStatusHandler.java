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
 * GET /smelt_status. Renders the {@link FurnaceRegistry} as a list of active smelts. Read-only —
 * the registry is mutated by {@link FurnaceTicker} (and by /smelt and /collect_smelt). This
 * handler does drop terminal entries after surfacing once: {@link FurnaceRegistry.Status#DESTROYED}
 * and {@link FurnaceRegistry.Status#EMPTY}. The agent gets one chance to learn the transition,
 * and subsequent /smelt_status calls won't repeat it.
 */
public final class SmeltStatusHandler implements HttpHandler {

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		try {
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				respond(exchange, 405, failure("bad_request", "method not allowed"));
				return;
			}

			List<FurnaceRegistry.Entry> entries = FurnaceRegistry.all();
			List<Map<String, Object>> wire = new ArrayList<>(entries.size());
			List<net.minecraft.core.BlockPos> drop = new ArrayList<>();

			for (FurnaceRegistry.Entry e : entries) {
				wire.add(FurnaceRegistry.toWire(e));
				if (e.status() == FurnaceRegistry.Status.DESTROYED
						|| e.status() == FurnaceRegistry.Status.EMPTY) {
					drop.add(e.pos());
				}
			}
			for (net.minecraft.core.BlockPos p : drop) {
				FurnaceRegistry.remove(p);
			}

			Map<String, Object> body = new LinkedHashMap<>();
			body.put("smelts", wire);
			respond(exchange, 200, body);
		} finally {
			exchange.close();
		}
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
