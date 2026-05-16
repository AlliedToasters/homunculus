package dev.toast.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * POST /smelt. Fire-and-forget per the v1.2 redesign:
 * <ol>
 *   <li>Pre-check (Smelts.preCheck) — recipe lookup, count cap, furnace proximity.</li>
 *   <li>If no furnace within reach, auto-place from inventory via {@link Placer}.</li>
 *   <li>Call {@link Smelter#ignite} — opens menu, evicts mismatched, pushes input + first fuel
 *       component (single-fuel only in v1.2), closes menu.</li>
 *   <li>Register in {@link FurnaceRegistry}; return immediately with the registry handle.</li>
 * </ol>
 *
 * <p>Failure reasons (no {@code requires_furnace}/{@code furnace_nearby} hint fields — the redesign
 * drops them in favor of precise reasons):
 * <ul>
 *   <li>{@code missing_input} / {@code missing_fuel} / {@code no_recipe} / {@code unknown_item} —
 *       from Smelts.</li>
 *   <li>{@code not_in_inventory} / {@code no_space} / {@code no_placeable_spot} — from Placer when
 *       auto-placement was invoked.</li>
 *   <li>{@code internal_error} — anything else.</li>
 * </ul>
 */
public final class SmeltHandler implements HttpHandler {
	private static final long PRECHECK_TIMEOUT_MS = 5000;
	private static final int MAX_BODY_BYTES = 4096;
	private static final String FURNACE_ITEM = "minecraft:furnace";

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		try {
			if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				respond(exchange, 405, failureBody("bad_request", "method not allowed"));
				return;
			}

			Request req;
			try {
				req = parseBody(exchange);
			} catch (IllegalArgumentException e) {
				respond(exchange, 400, failureBody("bad_request", "bad request: " + e.getMessage()));
				return;
			}

			Smelts.PreCheckResult pre;
			try {
				final Request fr = req;
				pre = ClientThread.supply(() -> Smelts.preCheck(fr.input, fr.count, fr.fuel))
						.get(PRECHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				respond(exchange, 504, failureBody("internal_error", "client thread timeout"));
				return;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				respond(exchange, 500, failureBody("internal_error", "interrupted"));
				return;
			} catch (ExecutionException e) {
				HomunculusClient.LOGGER.error("smelt pre-check failed", e.getCause());
				respond(exchange, 500, failureBody("internal_error", "pre-check threw: " + rootMessage(e)));
				return;
			}

			if (pre instanceof Smelts.Failure f) {
				respond(exchange, 200, failureFromSmelts(f));
				return;
			}
			Smelts.PreOk preOk = (Smelts.PreOk) pre;

			// Auto-place if no furnace within reach.
			if (!preOk.furnaceNearby()) {
				Placer.Result placeRes;
				try {
					placeRes = Placer.place(FURNACE_ITEM);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					respond(exchange, 500, failureBody("internal_error", "interrupted during auto-place"));
					return;
				} catch (RuntimeException e) {
					// Without this catch, any RuntimeException from Placer.place
					// propagates out, the finally{} closes the exchange WITHOUT a
					// response, and the Python client sees "Remote end closed
					// connection without response" — observed r10 8x consecutive
					// smelt failures (T18/19/22/24/25/26/38/39), agent never reached
					// iron tier. Surface a structured 500 instead.
					HomunculusClient.LOGGER.error("smelt auto-place failed", e);
					respond(exchange, 500, failureBody("internal_error", "auto-place threw: " + rootMessage(e)));
					return;
				}
				if (placeRes instanceof Placer.Failure pf) {
					respond(exchange, 200, failureBody(pf.reason(), pf.message()));
					return;
				}
				Placer.Ok placed = (Placer.Ok) placeRes;
				BlockPos newPos = new BlockPos(placed.x(), placed.y(), placed.z());
				preOk = new Smelts.PreOk(
						preOk.inputItem(), preOk.resultItem(),
						preOk.outputPerBatch(), preOk.batches(), preOk.totalOutput(),
						preOk.cookTicks(), newPos);
			}

			// Ignite.
			Smelter.IgniteResult ignite;
			try {
				ignite = Smelter.ignite(preOk, req.fuel);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				respond(exchange, 500, failureBody("internal_error", "interrupted"));
				return;
			} catch (RuntimeException e) {
				HomunculusClient.LOGGER.error("smelt ignite failed", e);
				respond(exchange, 500, failureBody("internal_error", "ignite threw: " + rootMessage(e)));
				return;
			}

			if (ignite instanceof Smelter.ValidationFailure vf) {
				respond(exchange, 200, failureFromSmelts(vf.inner()));
				return;
			}
			if (ignite instanceof Smelter.ExecutionFailure ef) {
				respond(exchange, 200, failureBody("internal_error", ef.message()));
				return;
			}
			Smelter.IgniteOk ok = (Smelter.IgniteOk) ignite;

			// Register in the furnace registry.
			ResourceKey<Level> dim = currentDimensionKey();
			if (dim == null) {
				respond(exchange, 500, failureBody("internal_error",
						"client level vanished before registration"));
				return;
			}
			FurnaceRegistry.Entry entry = new FurnaceRegistry.Entry(
					dim, ok.furnacePos(),
					ok.inputItem(), ok.inputLoaded(),
					ok.resultItem(), ok.outputExpected(),
					ok.fuelLoaded(),
					ok.cookTicksPerBatch(),
					ok.startedAtMs(),
					0L,
					FurnaceRegistry.Status.COOKING,
					ok.startedAtMs(),
					0,
					ok.inputLoaded(),
					sumFuelBurns(ok.fuelLoaded(), ok.cookTicksPerBatch()));
			FurnaceRegistry.put(entry);

			respond(exchange, 200, igniteResponse(ok));
		} finally {
			exchange.close();
		}
	}

	private static int sumFuelBurns(List<FurnaceRegistry.FuelLoaded> fuels, int cookTicksPerBatch) {
		long burnTicks = 0;
		for (FurnaceRegistry.FuelLoaded f : fuels) burnTicks += f.totalBurnTicks();
		return (int) (burnTicks / cookTicksPerBatch);
	}

	private static ResourceKey<Level> currentDimensionKey() {
		try {
			return ClientThread.supply(() -> {
				var mc = net.minecraft.client.Minecraft.getInstance();
				return mc.level != null ? mc.level.dimension() : null;
			}).get(2, TimeUnit.SECONDS);
		} catch (Exception e) {
			return null;
		}
	}

	private static Map<String, Object> igniteResponse(Smelter.IgniteOk ok) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("success", true);
		List<Object> posArr = new ArrayList<>(3);
		posArr.add(ok.furnacePos().getX());
		posArr.add(ok.furnacePos().getY());
		posArr.add(ok.furnacePos().getZ());
		body.put("furnace_pos", posArr);

		Map<String, Object> input = new LinkedHashMap<>();
		input.put("id", ok.inputItem().toString());
		input.put("count", ok.inputLoaded());
		body.put("input", input);

		Map<String, Object> expected = new LinkedHashMap<>();
		expected.put("id", ok.resultItem().toString());
		expected.put("count", ok.outputExpected());
		body.put("expected_output", expected);

		List<Object> fuelArr = new ArrayList<>();
		for (FurnaceRegistry.FuelLoaded f : ok.fuelLoaded()) {
			Map<String, Object> e = new LinkedHashMap<>();
			e.put("id", f.id().toString());
			e.put("count", f.count());
			fuelArr.add(e);
		}
		body.put("fuel_loaded", fuelArr);

		long etaTicks = (long) ok.outputExpected() * ok.cookTicksPerBatch();
		body.put("eta_seconds", etaTicks / 20L);
		body.put("status", "cooking");
		body.put("started_at_ms", ok.startedAtMs());

		if (ok.fuelCapped()) {
			body.put("message", "fuel-capped: started " + ok.inputLoaded() + " of "
					+ ok.requestedBatches() + " requested smelts (loaded fuel covers " + ok.inputLoaded()
					+ "); top up fuel and call /smelt again with the remainder");
		}
		return body;
	}

	private record Request(String input, int count, String fuel) {}

	private static Request parseBody(HttpExchange exchange) throws IOException {
		byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
		if (bytes.length > MAX_BODY_BYTES) throw new IllegalArgumentException("body too large");
		String body = new String(bytes, StandardCharsets.UTF_8).trim();
		if (body.isEmpty()) throw new IllegalArgumentException("empty body");
		Object parsed;
		try {
			parsed = Json.parse(body);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("invalid JSON (" + e.getMessage() + ")");
		}
		if (!(parsed instanceof Map<?, ?> m)) throw new IllegalArgumentException("expected JSON object");

		Object inputRaw = m.get("input");
		if (!(inputRaw instanceof String input) || input.isEmpty()) {
			throw new IllegalArgumentException("'input' must be a non-empty string");
		}
		Object countRaw = m.get("count");
		int count;
		if (countRaw == null) {
			count = 1;
		} else if (countRaw instanceof Number n) {
			long lv = n.longValue();
			if (lv < 1 || lv > 64) throw new IllegalArgumentException("'count' must be in [1, 64]");
			count = (int) lv;
		} else {
			throw new IllegalArgumentException("'count' must be a number");
		}
		Object fuelRaw = m.get("fuel");
		String fuel = null;
		if (fuelRaw != null) {
			if (!(fuelRaw instanceof String fs) || fs.isEmpty()) {
				throw new IllegalArgumentException("'fuel' must be a non-empty string when present");
			}
			fuel = fs;
		}
		return new Request(input, count, fuel);
	}

	/** Convert a Smelts.Failure into the v1.2 response (no requires_furnace/furnace_nearby fields). */
	private static Map<String, Object> failureFromSmelts(Smelts.Failure f) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("success", false);
		body.put("reason", f.reason());
		List<Object> miss = new ArrayList<>();
		if (f.missing() != null) {
			for (Smelts.MissingItem m : f.missing()) {
				Map<String, Object> entry = new LinkedHashMap<>();
				entry.put("id", m.id());
				entry.put("count", m.count());
				miss.add(entry);
			}
		}
		body.put("missing", miss);
		body.put("message", f.message());
		return body;
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
		return (m == null ? c.getClass().getSimpleName() : m);
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
