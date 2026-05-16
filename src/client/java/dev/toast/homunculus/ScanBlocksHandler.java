package dev.toast.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ScanBlocksHandler implements HttpHandler {
	private static final long SNAPSHOT_TIMEOUT_MS = 5000;
	private static final int MAX_VOLUME = 2000;

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

			final int x1, y1, z1, x2, y2, z2;
			try {
				x1 = requireInt(q, "x1");
				y1 = requireInt(q, "y1");
				z1 = requireInt(q, "z1");
				x2 = requireInt(q, "x2");
				y2 = requireInt(q, "y2");
				z2 = requireInt(q, "z2");
			} catch (IllegalArgumentException e) {
				respond(exchange, 400, failure("invalid_request", e.getMessage()));
				return;
			}

			int[] box = {
					Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
					Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2)
			};
			long volume = (long) (box[3] - box[0] + 1)
					* (box[4] - box[1] + 1)
					* (box[5] - box[2] + 1);
			if (volume > MAX_VOLUME) {
				respond(exchange, 400, failure("invalid_request",
						"volume " + volume + " exceeds cap " + MAX_VOLUME));
				return;
			}

			Map<String, Object> body;
			try {
				body = ClientThread.supply(() -> scan(box, (int) volume))
						.get(SNAPSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				respond(exchange, 504, failure("timeout", "client thread timeout"));
				return;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				respond(exchange, 500, failure("internal_error", "interrupted"));
				return;
			} catch (ExecutionException e) {
				HomunculusClient.LOGGER.error("scan_blocks failed", e.getCause());
				respond(exchange, 500, failure("internal_error", "internal error"));
				return;
			}

			int status = 200;
			if (Boolean.FALSE.equals(body.get("success"))) {
				Object r = body.get("reason");
				if ("out_of_range".equals(r)) status = 400;
				else status = 503;
			}
			respond(exchange, status, body);
		} finally {
			exchange.close();
		}
	}

	/** Runs on the client/render thread. */
	private static Map<String, Object> scan(int[] box, int volume) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) {
			return failure("internal_error", "no client level (not in world)");
		}

		int minChunkX = box[0] >> 4;
		int minChunkZ = box[2] >> 4;
		int maxChunkX = box[3] >> 4;
		int maxChunkZ = box[5] >> 4;
		for (int cx = minChunkX; cx <= maxChunkX; cx++) {
			for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
				if (!level.hasChunk(cx, cz)) {
					return failure("out_of_range",
							"chunk (" + cx + "," + cz + ") is not loaded; move closer and retry");
				}
			}
		}

		List<Map<String, Object>> blocks = new ArrayList<>();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int y = box[1]; y <= box[4]; y++) {
			for (int z = box[2]; z <= box[5]; z++) {
				for (int x = box[0]; x <= box[3]; x++) {
					cursor.set(x, y, z);
					BlockState bs = level.getBlockState(cursor);
					if (bs.isAir()) continue;
					Map<String, Object> rec = new LinkedHashMap<>(4);
					rec.put("x", x);
					rec.put("y", y);
					rec.put("z", z);
					rec.put("id", BuiltInRegistries.BLOCK.getKey(bs.getBlock()).toString());
					rec.put("passable", bs.getCollisionShape(level, cursor).isEmpty());
					blocks.add(rec);
				}
			}
		}

		Map<String, Object> result = new LinkedHashMap<>();
		List<Object> boxOut = new ArrayList<>(6);
		for (int v : box) boxOut.add(v);
		result.put("box", boxOut);
		result.put("volume", volume);
		result.put("blocks", blocks);
		return result;
	}

	private static int requireInt(Map<String, String> q, String key) {
		String v = q.get(key);
		if (v == null) throw new IllegalArgumentException("missing required query param: " + key);
		try {
			return Integer.parseInt(v);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(key + " must be an integer");
		}
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
