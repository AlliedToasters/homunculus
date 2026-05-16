package dev.toast.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * POST /collect_smelt. Routes the player to a registered furnace via {@link Goto#run}, then opens
 * the furnace UI and withdraws ready output. Reconciles the registry entry against actual slot
 * contents — for stale entries this is the first observation since the chunk reloaded.
 *
 * <p>The registry entry survives a successful collect if input is still in the furnace
 * (partially-cooked batch where fuel ran out before all input cooked, or a still-cooking batch
 * where the agent collected early). The entry is dropped only when the furnace input slot is empty
 * after collection.
 */
public final class CollectSmeltHandler implements HttpHandler {
	private static final int MAX_BODY_BYTES = 4096;
	private static final long GOTO_TIMEOUT_SECONDS = 120;
	private static final long ARRIVAL_TOLERANCE = 4;  // matches furnace reach in Smelts

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		try {
			if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				respond(exchange, 405, failure("bad_request", "method not allowed", null));
				return;
			}

			Request req;
			try {
				req = parseBody(exchange);
			} catch (IllegalArgumentException e) {
				respond(exchange, 400, failure("bad_request", "bad request: " + e.getMessage(), null));
				return;
			}

			// Pick target entry. Either explicit furnace_pos or closest collectable.
			FurnaceRegistry.Entry target;
			if (req.furnacePos != null) {
				target = FurnaceRegistry.get(req.furnacePos);
				if (target == null) {
					respond(exchange, 200, failure("not_in_registry",
							"no registered smelt at " + posStr(req.furnacePos),
							req.furnacePos));
					return;
				}
			} else {
				target = pickClosest();
				if (target == null) {
					respond(exchange, 200, failure("no_active_smelts",
							"no registered smelts with status ∈ {ready, partial, stale}",
							null));
					return;
				}
			}

			// Cross-dimension entries fail goto in v1 (goto doesn't cross dims).
			ResourceLocation playerDim = currentDimension();
			ResourceLocation entryDim = target.dimension().location();
			if (playerDim == null || !playerDim.equals(entryDim)) {
				respond(exchange, 200, failure("furnace_unreachable",
						"furnace is in dimension " + entryDim + " but player is in "
								+ (playerDim != null ? playerDim.toString() : "no-dim")
								+ "; cross-dimension routing is not supported in v1",
						target.pos()));
				return;
			}

			// Route to within reach.
			Goto.Outcome routeOutcome = Goto.run(
					target.pos().getX(), target.pos().getY(), target.pos().getZ(),
					GOTO_TIMEOUT_SECONDS, ARRIVAL_TOLERANCE);
			if (routeOutcome instanceof Goto.Failed gf) {
				respond(exchange, 200, failure("furnace_unreachable",
						"goto failed (" + gf.reason() + "): " + gf.message(),
						target.pos()));
				return;
			}

			// Reconcile: is the block still a furnace?
			boolean stillFurnace;
			try {
				stillFurnace = ClientThread.supply(() ->
						FurnaceRegistry.isStillFurnace(target.pos())
				).get(2, java.util.concurrent.TimeUnit.SECONDS);
			} catch (Exception e) {
				HomunculusClient.LOGGER.warn("collect_smelt: chunk-load check failed", e);
				respond(exchange, 200, failure("internal_error",
						"failed to verify furnace presence: " + e.getMessage(),
						target.pos()));
				return;
			}
			if (!stillFurnace) {
				FurnaceRegistry.remove(target.pos());
				respond(exchange, 200, failure("furnace_destroyed",
						"block at " + posStr(target.pos()) + " is no longer a furnace",
						target.pos()));
				return;
			}

			// Open furnace, withdraw output, peek input/fuel for reconciliation.
			Smelter.CollectResult cr;
			try {
				cr = Smelter.collectFromFurnace(target.pos());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				respond(exchange, 200, failure("internal_error", "interrupted during collect",
						target.pos()));
				return;
			}

			if (cr instanceof Smelter.CollectFailure cf) {
				respond(exchange, 200, failure(cf.reason(), cf.message(), target.pos()));
				return;
			}
			Smelter.CollectOk ok = (Smelter.CollectOk) cr;

			if (ok.collectedCount() <= 0 && ok.inputRemaining() > 0) {
				// Reconciliation revealed still-cooking with nothing in the output slot.
				updateAfterCollect(target, ok);
				respond(exchange, 200, failure("nothing_ready",
						"furnace is still cooking; " + ok.inputRemaining()
								+ " input remaining, no output ready",
						target.pos()));
				return;
			}

			// Successful collection — update registry, return success.
			respond(exchange, 200, successBody(target, ok));
			updateAfterCollect(target, ok);
		} finally {
			exchange.close();
		}
	}

	/**
	 * Reconcile registry state after the collect. If input is exhausted, drop the entry; else
	 * refresh the clock and remaining counts and continue tracking. Status flips based on whether
	 * any fuel remains.
	 */
	private static void updateAfterCollect(FurnaceRegistry.Entry target, Smelter.CollectOk ok) {
		if (ok.inputRemaining() <= 0) {
			FurnaceRegistry.remove(target.pos());
			return;
		}

		// Cook continues. Reset the cook clock to now (we've collected what was ready) and recompute
		// expected_output from what fuel can still cover.
		int newExpected = Math.min(ok.inputRemaining(), ok.fuelRemainingBurns());
		FurnaceRegistry.Status newStatus =
				(ok.fuelRemainingBurns() > 0 && newExpected > 0)
						? FurnaceRegistry.Status.COOKING
						: FurnaceRegistry.Status.PARTIAL;
		long now = System.currentTimeMillis();

		// Synthesize a fresh entry with the residual cook scope. Fuel-loaded list is updated to
		// reflect remaining fuel as one component of the previously-loaded type. We don't know the
		// burn-per-piece for the *remaining* fuel without re-reading the furnace stack; use the
		// previously-recorded burn-per-piece from the original fuelLoaded entry as a proxy. The
		// fuel slot is single-stack so this is correct for v1.2's single-fuel-type plan.
		List<FurnaceRegistry.FuelLoaded> fuelLoaded;
		if (!target.fuelLoaded().isEmpty()) {
			FurnaceRegistry.FuelLoaded prev = target.fuelLoaded().get(0);
			// Convert burns-remaining (cook-batch units) back to pieces.
			int burnsPerPiece = prev.burnTicksEach() / target.cookTicksPerBatch();
			int pieces = burnsPerPiece > 0
					? (ok.fuelRemainingBurns() + burnsPerPiece - 1) / burnsPerPiece
					: 0;
			fuelLoaded = List.of(new FurnaceRegistry.FuelLoaded(
					prev.id(), Math.max(0, pieces), prev.burnTicksEach()));
		} else {
			fuelLoaded = List.of();
		}

		FurnaceRegistry.Entry updated = new FurnaceRegistry.Entry(
				target.dimension(), target.pos(),
				target.inputItem(),
				ok.inputRemaining(),                    // new initial
				target.resultItem(),
				Math.max(0, newExpected),
				fuelLoaded,
				target.cookTicksPerBatch(),
				now,                                     // reset wall-clock display
				0L,                                      // reset cook clock
				newStatus,
				now,
				0,
				ok.inputRemaining(),
				ok.fuelRemainingBurns());
		FurnaceRegistry.put(updated);
	}

	private static FurnaceRegistry.Entry pickClosest() {
		Minecraft mc = Minecraft.getInstance();
		try {
			return ClientThread.supply(() -> {
				LocalPlayer p = mc.player;
				if (p == null || mc.level == null) return null;
				ResourceLocation dim = mc.level.dimension().location();
				FurnaceRegistry.Entry best = null;
				double bestDistSq = Double.MAX_VALUE;
				for (FurnaceRegistry.Entry e : FurnaceRegistry.all()) {
					if (!e.dimension().location().equals(dim)) continue;
					switch (e.status()) {
						case READY, PARTIAL, STALE -> { /* eligible */ }
						default -> { continue; }
					}
					double d = e.pos().distSqr(p.blockPosition());
					if (d < bestDistSq) {
						best = e;
						bestDistSq = d;
					}
				}
				return best;
			}).get(2, java.util.concurrent.TimeUnit.SECONDS);
		} catch (Exception e) {
			HomunculusClient.LOGGER.warn("collect_smelt: pickClosest failed", e);
			return null;
		}
	}

	private static ResourceLocation currentDimension() {
		Minecraft mc = Minecraft.getInstance();
		try {
			return ClientThread.supply(() -> {
				if (mc.level == null) return null;
				return mc.level.dimension().location();
			}).get(2, java.util.concurrent.TimeUnit.SECONDS);
		} catch (Exception e) {
			return null;
		}
	}

	private record Request(BlockPos furnacePos) {}

	private static Request parseBody(HttpExchange exchange) throws IOException {
		byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
		if (bytes.length > MAX_BODY_BYTES) throw new IllegalArgumentException("body too large");
		String body = new String(bytes, StandardCharsets.UTF_8).trim();
		if (body.isEmpty()) return new Request(null);
		Object parsed;
		try {
			parsed = Json.parse(body);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("invalid JSON (" + e.getMessage() + ")");
		}
		if (!(parsed instanceof Map<?, ?> m)) throw new IllegalArgumentException("expected JSON object");

		Object raw = m.get("furnace_pos");
		if (raw == null) return new Request(null);
		if (!(raw instanceof List<?> arr)) throw new IllegalArgumentException("'furnace_pos' must be a 3-element array");
		if (arr.size() != 3) throw new IllegalArgumentException("'furnace_pos' must be a 3-element array");
		int x = requireInt(arr.get(0), "furnace_pos[0]");
		int y = requireInt(arr.get(1), "furnace_pos[1]");
		int z = requireInt(arr.get(2), "furnace_pos[2]");
		return new Request(new BlockPos(x, y, z));
	}

	private static int requireInt(Object raw, String label) {
		if (!(raw instanceof Number n)) throw new IllegalArgumentException("'" + label + "' must be an integer");
		if (n.doubleValue() != n.longValue()) throw new IllegalArgumentException("'" + label + "' must be an integer");
		return (int) n.longValue();
	}

	private static Map<String, Object> successBody(FurnaceRegistry.Entry target, Smelter.CollectOk ok) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("success", true);
		body.put("furnace_pos", posList(target.pos()));

		List<Map<String, Object>> collected = new ArrayList<>();
		if (ok.collectedItem() != null && ok.collectedCount() > 0) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", ok.collectedItem().toString());
			item.put("count", ok.collectedCount());
			collected.add(item);
		}
		body.put("collected", collected);
		body.put("still_cooking", ok.inputRemaining());
		body.put("fuel_remaining_burns", ok.fuelRemainingBurns());

		// Post-collection status.
		String status;
		if (ok.inputRemaining() <= 0) {
			status = "empty";
		} else if (ok.fuelRemainingBurns() > 0) {
			status = "cooking";
			// ETA = min(input_remaining, fuel_remaining_burns) cook cycles, 10s/cycle at default 200 ticks.
			int cycles = Math.min(ok.inputRemaining(), ok.fuelRemainingBurns());
			body.put("eta_seconds", cycles * target.cookTicksPerBatch() / 20L);
		} else {
			status = "partial";
		}
		body.put("status", status);
		return body;
	}

	private static Map<String, Object> failure(String reason, String message, BlockPos pos) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("success", false);
		m.put("reason", reason);
		if (pos != null) m.put("furnace_pos", posList(pos));
		m.put("message", message);
		return m;
	}

	private static List<Object> posList(BlockPos pos) {
		List<Object> l = new ArrayList<>(3);
		l.add(pos.getX()); l.add(pos.getY()); l.add(pos.getZ());
		return l;
	}

	private static String posStr(BlockPos pos) {
		return "(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")";
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
