package dev.klear.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class PositionHandler implements HttpHandler {
	private static final long SNAPSHOT_TIMEOUT_MS = 2000;

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		try {
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				respond(exchange, 405, failure("internal_error", "method not allowed"));
				return;
			}

			Map<String, Object> body;
			try {
				body = ClientThread.supply(PositionHandler::snapshot)
						.get(SNAPSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				respond(exchange, 504, failure("internal_error", "client thread timeout"));
				return;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				respond(exchange, 500, failure("internal_error", "interrupted"));
				return;
			} catch (ExecutionException e) {
				HomunculusClient.LOGGER.error("position snapshot failed", e.getCause());
				respond(exchange, 500, failure("internal_error", "internal error"));
				return;
			}

			if (body == null) {
				respond(exchange, 503, failure("internal_error", "no player (not in world)"));
				return;
			}
			respond(exchange, 200, body);
		} finally {
			exchange.close();
		}
	}

	/** Runs on the client/render thread. Returns null if the player is not in a world. */
	private static Map<String, Object> snapshot() {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null) return null;
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("x", p.getX());
		m.put("y", p.getY());
		m.put("z", p.getZ());
		m.put("yaw", (double) p.getYRot());
		m.put("pitch", (double) p.getXRot());
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
