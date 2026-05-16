package dev.toast.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// Find the nearest instance of each requested block id within a bounding box
// around the player. Returns only the best match per id (or null if none),
// avoiding the /scan_blocks volume cap and per-block serialization cost.
//
// Driving use case: mine_wood candidate cycling. craft probes all 11 LOG_TYPES
// in one round-trip, drops absent species, sorts by distance, and lets the
// /baritone/mine cycle skip ~45s per absent variant.
public final class ScanNearestHandler implements HttpHandler {
	private static final long SNAPSHOT_TIMEOUT_MS = 5000;
	private static final int DEFAULT_RADIUS = 32;
	private static final int DEFAULT_Y_RADIUS = 8;
	private static final int MAX_RADIUS = 64;
	private static final int MAX_Y_RADIUS = 64;

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

			String idsRaw = q.get("ids");
			if (idsRaw == null || idsRaw.isEmpty()) {
				respond(exchange, 400, failure("invalid_request", "missing required query param: ids"));
				return;
			}
			List<String> requestedIds = new ArrayList<>();
			for (String part : idsRaw.split(",")) {
				String s = part.trim();
				if (!s.isEmpty()) requestedIds.add(s);
			}
			if (requestedIds.isEmpty()) {
				respond(exchange, 400, failure("invalid_request", "ids must contain at least one block id"));
				return;
			}

			int radius;
			int yRadius;
			try {
				radius = readBoundedInt(q, "radius", DEFAULT_RADIUS, 1, MAX_RADIUS);
				yRadius = readBoundedInt(q, "y_radius", DEFAULT_Y_RADIUS, 1, MAX_Y_RADIUS);
			} catch (IllegalArgumentException e) {
				respond(exchange, 400, failure("invalid_request", e.getMessage()));
				return;
			}

			final int rxz = radius;
			final int ry = yRadius;
			Map<String, Object> body;
			try {
				body = ClientThread.supply(() -> scan(requestedIds, rxz, ry))
						.get(SNAPSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				respond(exchange, 504, failure("timeout", "client thread timeout"));
				return;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				respond(exchange, 500, failure("internal_error", "interrupted"));
				return;
			} catch (ExecutionException e) {
				HomunculusClient.LOGGER.error("scan_nearest failed", e.getCause());
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
	private static Map<String, Object> scan(List<String> requestedIds, int radius, int yRadius) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (p == null || level == null) {
			return failure("internal_error", "no player (not in world)");
		}

		int px = (int) Math.floor(p.getX());
		int py = (int) Math.floor(p.getY());
		int pz = (int) Math.floor(p.getZ());

		int x1 = px - radius, x2 = px + radius;
		int y1 = py - yRadius, y2 = py + yRadius;
		int z1 = pz - radius, z2 = pz + radius;

		// Clamp y to world bounds.
		int worldMin = level.getMinY();
		int worldMax = level.getMaxY();
		if (y1 < worldMin) y1 = worldMin;
		if (y2 > worldMax) y2 = worldMax;

		// Resolve requested ids to Block instances. Unknown ids stay as null
		// matches in the response so the caller can distinguish "id doesn't
		// exist" (typo) from "id exists but not in range".
		Map<String, Block> resolved = new LinkedHashMap<>();
		List<String> unknown = new ArrayList<>();
		for (String id : requestedIds) {
			ResourceLocation key;
			try {
				key = ResourceLocation.parse(id);
			} catch (Exception e) {
				unknown.add(id);
				continue;
			}
			Optional<Block> opt = BuiltInRegistries.BLOCK.getOptional(key);
			if (opt.isPresent()) {
				resolved.put(id, opt.get());
			} else {
				unknown.add(id);
			}
		}

		// Per-id best-match state: squared distance, x, y, z.
		// We track squared distance during the hot loop and sqrt once at the end.
		Map<String, long[]> bestByBlock = new LinkedHashMap<>();
		for (String id : resolved.keySet()) {
			bestByBlock.put(id, null);
		}
		// Block→id reverse map (multiple ids could resolve to the same block,
		// though unlikely; we keep the first id wins).
		Map<Block, String> blockToId = new LinkedHashMap<>();
		for (Map.Entry<String, Block> e : resolved.entrySet()) {
			blockToId.putIfAbsent(e.getValue(), e.getKey());
		}

		// Verify all touched chunks are loaded. If any chunk in the box is
		// missing, fail out rather than silently returning a partial scan.
		int minChunkX = x1 >> 4;
		int minChunkZ = z1 >> 4;
		int maxChunkX = x2 >> 4;
		int maxChunkZ = z2 >> 4;
		for (int cx = minChunkX; cx <= maxChunkX; cx++) {
			for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
				if (!level.hasChunk(cx, cz)) {
					return failure("out_of_range",
							"chunk (" + cx + "," + cz + ") is not loaded; move closer or shrink radius");
				}
			}
		}

		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		long scanned = 0;
		for (int y = y1; y <= y2; y++) {
			int dy = y - py;
			long dy2 = (long) dy * dy;
			for (int z = z1; z <= z2; z++) {
				int dz = z - pz;
				long dz2 = (long) dz * dz;
				for (int x = x1; x <= x2; x++) {
					scanned++;
					cursor.set(x, y, z);
					BlockState bs = level.getBlockState(cursor);
					if (bs.isAir()) continue;
					Block b = bs.getBlock();
					String id = blockToId.get(b);
					if (id == null) continue;
					int dx = x - px;
					long d2 = (long) dx * dx + dy2 + dz2;
					long[] cur = bestByBlock.get(id);
					if (cur == null || d2 < cur[0]) {
						bestByBlock.put(id, new long[]{d2, x, y, z});
					}
				}
			}
		}

		Map<String, Object> matches = new LinkedHashMap<>();
		// Preserve caller's id order in the response.
		for (String id : requestedIds) {
			if (unknown.contains(id) || !bestByBlock.containsKey(id)) {
				matches.put(id, null);
				continue;
			}
			long[] best = bestByBlock.get(id);
			if (best == null) {
				matches.put(id, null);
				continue;
			}
			Map<String, Object> rec = new LinkedHashMap<>(4);
			rec.put("x", (int) best[1]);
			rec.put("y", (int) best[2]);
			rec.put("z", (int) best[3]);
			rec.put("distance", Math.sqrt((double) best[0]));
			matches.put(id, rec);
		}

		Map<String, Object> result = new LinkedHashMap<>();
		List<Object> origin = new ArrayList<>(3);
		origin.add(px);
		origin.add(py);
		origin.add(pz);
		result.put("origin", origin);
		result.put("radius", radius);
		result.put("y_radius", yRadius);
		result.put("scanned", scanned);
		result.put("matches", matches);
		if (!unknown.isEmpty()) result.put("unknown_ids", unknown);
		return result;
	}

	private static int readBoundedInt(Map<String, String> q, String key, int def, int min, int max) {
		String v = q.get(key);
		if (v == null || v.isEmpty()) return def;
		int n;
		try {
			n = Integer.parseInt(v);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(key + " must be an integer");
		}
		if (n < min || n > max) {
			throw new IllegalArgumentException(key + " must be in [" + min + "," + max + "]");
		}
		return n;
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
