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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * POST /smelt. Body: {input, count, fuel?}. Two-phase: cheap pre-check (recipe + proximity) on the
 * client thread; if that passes, hand the PreOk to Smelter which opens the furnace, peeks slot
 * contents, then runs the full inventory + fuel budget with pre-loaded items factored in. Failure
 * paths follow the same structured shape as /craft.
 */
public final class SmeltHandler implements HttpHandler {
	private static final long PRECHECK_TIMEOUT_MS = 5000;
	private static final int MAX_BODY_BYTES = 4096;

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		try {
			if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				respond(exchange, 405, Map.of("error", "method not allowed"));
				return;
			}

			Request req;
			try {
				req = parseBody(exchange);
			} catch (IllegalArgumentException e) {
				respond(exchange, 400, Map.of("error", "bad request: " + e.getMessage()));
				return;
			}

			Smelts.PreCheckResult pre;
			try {
				final Request fr = req;
				pre = ClientThread.supply(() -> Smelts.preCheck(fr.input, fr.count, fr.fuel))
						.get(PRECHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				respond(exchange, 504, Map.of("error", "client thread timeout"));
				return;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				respond(exchange, 500, Map.of("error", "interrupted"));
				return;
			} catch (ExecutionException e) {
				HomunculusClient.LOGGER.error("smelt pre-check failed", e.getCause());
				respond(exchange, 500, failureBody("internal_error",
						"pre-check threw: " + rootMessage(e), false, List.of()));
				return;
			}

			if (pre instanceof Smelts.Failure f) {
				respond(exchange, 200, failureBody(f));
				return;
			}
			Smelts.PreOk preOk = (Smelts.PreOk) pre;

			Smelter.Result execResult;
			try {
				execResult = Smelter.execute(preOk, req.fuel);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				respond(exchange, 500, Map.of("error", "interrupted"));
				return;
			} catch (RuntimeException e) {
				HomunculusClient.LOGGER.error("smelt execution failed", e);
				respond(exchange, 500, failureBody("internal_error",
						"execution threw: " + rootMessage(e), preOk.furnaceNearby(), List.of()));
				return;
			}

			if (execResult instanceof Smelter.Ok eok) {
				Map<String, Object> body = new LinkedHashMap<>();
				body.put("success", true);
				Map<String, Object> smelted = new LinkedHashMap<>();
				smelted.put("id", eok.resultId());
				smelted.put("count", eok.resultCount());
				body.put("smelted", smelted);
				List<Object> fuels = new ArrayList<>();
				for (Smelter.FuelUse fu : eok.fuelUsed()) {
					Map<String, Object> entry = new LinkedHashMap<>();
					entry.put("id", fu.id());
					entry.put("count", fu.count());
					fuels.add(entry);
				}
				body.put("fuel_consumed", fuels);
				respond(exchange, 200, body);
			} else if (execResult instanceof Smelter.ValidationFailure vf) {
				respond(exchange, 200, failureBody(vf.inner()));
			} else if (execResult instanceof Smelter.ExecutionFailure ef) {
				respond(exchange, 200, failureBody("internal_error",
						ef.message(), preOk.furnaceNearby(), List.of()));
			}
		} finally {
			exchange.close();
		}
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

	private static Map<String, Object> failureBody(Smelts.Failure f) {
		return failureBody(f.reason(), f.message(), f.furnaceNearby(), f.missing());
	}

	private static Map<String, Object> failureBody(String reason, String message,
			boolean furnaceNearby, List<Smelts.MissingItem> missing) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("success", false);
		body.put("reason", reason);
		List<Object> miss = new ArrayList<>();
		if (missing != null) {
			for (Smelts.MissingItem m : missing) {
				Map<String, Object> entry = new LinkedHashMap<>();
				entry.put("id", m.id());
				entry.put("count", m.count());
				miss.add(entry);
			}
		}
		body.put("missing", miss);
		body.put("requires_furnace", true);
		body.put("furnace_nearby", furnaceNearby);
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
