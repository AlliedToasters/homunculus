package dev.toast.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * GET /debug/door_courtesy. Returns a JSON snapshot of {@link DoorCourtesy} state:
 * tick counter, current queue, lastSeenOpen contents, and the last 256 events.
 *
 * <p>Intended for debugging the close-the-door-behind-you behavior. Curl at the
 * moment of failure and the event ring buffer tells you what the scanner observed
 * and what actions fired.
 */
public final class DoorCourtesyDebugHandler implements HttpHandler {
	@Override
	public void handle(HttpExchange exchange) throws IOException {
		try {
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				respond(exchange, 405, Map.of("error", "method not allowed"));
				return;
			}
			Map<String, Object> snap = DoorCourtesy.snapshot();
			respond(exchange, 200, snap);
		} finally {
			exchange.close();
		}
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
