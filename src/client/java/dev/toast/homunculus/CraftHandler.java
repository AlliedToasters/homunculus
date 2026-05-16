package dev.toast.homunculus;

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
 * POST /craft. v1 wedge: structured pre-check only. Recipe lookup, ingredient gap, and
 * crafting-table proximity all run on the client thread; execution (placing the recipe and
 * pulling output) is the next wedge.
 */
public final class CraftHandler implements HttpHandler {
	private static final long EVAL_TIMEOUT_MS = 5000;
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

			Recipes.Result result;
			try {
				final Request fr = req;
				result = ClientThread.supply(() -> Recipes.evaluate(fr.item, fr.count))
						.get(EVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				respond(exchange, 504, Map.of("error", "client thread timeout"));
				return;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				respond(exchange, 500, Map.of("error", "interrupted"));
				return;
			} catch (ExecutionException e) {
				HomunculusClient.LOGGER.error("recipe evaluation failed", e.getCause());
				respond(exchange, 500, failureBody("internal_error",
						"recipe evaluation threw: " + rootMessage(e), false, false, List.of()));
				return;
			}

			if (result instanceof Recipes.Failure f) {
				respond(exchange, 200, failureBody(f));
			} else if (result instanceof Recipes.Ok ok) {
				Crafter.Result execResult;
				try {
					execResult = ok.is3x3() ? Crafter.execute3x3(ok) : Crafter.execute2x2(ok);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					respond(exchange, 500, Map.of("error", "interrupted"));
					return;
				} catch (RuntimeException e) {
					HomunculusClient.LOGGER.error("craft execution failed", e);
					respond(exchange, 500, executionFailureBody(ok, "execution threw: " + rootMessage(e)));
					return;
				}
				if (execResult instanceof Crafter.Ok eok) {
					Map<String, Object> body = new LinkedHashMap<>();
					body.put("success", true);
					Map<String, Object> crafted = new LinkedHashMap<>();
					crafted.put("id", eok.id());
					crafted.put("count", eok.count());
					body.put("crafted", crafted);
					respond(exchange, 200, body);
				} else if (execResult instanceof Crafter.Failure ef) {
					Map<String, Object> body = new LinkedHashMap<>();
					body.put("success", false);
					body.put("reason", ef.reason());
					body.put("missing", List.of());
					body.put("requires_crafting_table", ok.requiresCraftingTable());
					body.put("crafting_table_nearby", ok.craftingTableNearby());
					body.put("message", ef.message());
					if (ef.id() != null) {
						Map<String, Object> partial = new LinkedHashMap<>();
						partial.put("id", ef.id());
						partial.put("count", ef.crafted());
						body.put("partial", partial);
					}
					respond(exchange, 200, body);
				}
			}
		} finally {
			exchange.close();
		}
	}

	private record Request(String item, int count) {}

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

		Object itemRaw = m.get("item");
		if (!(itemRaw instanceof String item) || item.isEmpty()) {
			throw new IllegalArgumentException("'item' must be a non-empty string");
		}
		Object countRaw = m.get("count");
		int count;
		if (countRaw == null) {
			count = 1;
		} else if (countRaw instanceof Number n) {
			long lv = n.longValue();
			if (lv < 1 || lv > 1024) throw new IllegalArgumentException("'count' must be in [1, 1024]");
			count = (int) lv;
		} else {
			throw new IllegalArgumentException("'count' must be a number");
		}
		return new Request(item, count);
	}

	private static Map<String, Object> failureBody(Recipes.Failure f) {
		return failureBody(f.reason(), f.message(), f.requiresCraftingTable(), f.craftingTableNearby(), f.missing());
	}

	private static Map<String, Object> failureBody(String reason, String message,
			boolean requiresTable, boolean tableNearby, List<Recipes.MissingItem> missing) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("success", false);
		body.put("reason", reason);
		List<Object> miss = new ArrayList<>();
		if (missing != null) {
			for (Recipes.MissingItem m : missing) {
				Map<String, Object> entry = new LinkedHashMap<>();
				entry.put("id", m.id());
				entry.put("count", m.count());
				miss.add(entry);
			}
		}
		body.put("missing", miss);
		body.put("requires_crafting_table", requiresTable);
		body.put("crafting_table_nearby", tableNearby);
		body.put("message", message);
		return body;
	}

	private static Map<String, Object> executionFailureBody(Recipes.Ok ok, String message) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("success", false);
		body.put("reason", "internal_error");
		body.put("missing", List.of());
		body.put("requires_crafting_table", ok.requiresCraftingTable());
		body.put("crafting_table_nearby", ok.craftingTableNearby());
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
