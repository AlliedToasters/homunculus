package dev.toast.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * POST /bed/sleep. Body: {"max_radius": 6} (all fields optional).
 *
 * Finds the nearest bed within max_radius, validates sleep preconditions, and right-clicks it.
 * Returns immediately on success — does NOT block until dawn. The agent polls GET /stats
 * is_sleeping to wait for wake.
 */
public final class SleepHandler implements HttpHandler {
    private static final int MAX_BODY_BYTES = 4096;
    private static final int DEFAULT_MAX_RADIUS = 6;

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, Map.of("error", "method not allowed"));
                return;
            }

            int maxRadius = parseMaxRadius(exchange);

            Sleeper.Result result;
            try {
                result = Sleeper.sleep(maxRadius);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                respond(exchange, 500, Map.of("error", "interrupted"));
                return;
            } catch (RuntimeException e) {
                HomunculusClient.LOGGER.error("bed/sleep failed", e);
                respond(exchange, 200, failureBody("internal_error", "bed/sleep threw: " + rootMessage(e)));
                return;
            }

            if (result instanceof Sleeper.Ok ok) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("success", true);
                body.put("bed", List.of(ok.x(), ok.y(), ok.z()));
                body.put("day_ticks_at_sleep", ok.dayTicksAtSleep());
                respond(exchange, 200, body);
            } else if (result instanceof Sleeper.Failure f) {
                respond(exchange, 200, failureBody(f.reason(), f.message()));
            }
        } finally {
            exchange.close();
        }
    }

    private static int parseMaxRadius(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) return DEFAULT_MAX_RADIUS;
        String body = new String(bytes, StandardCharsets.UTF_8).trim();
        if (body.isEmpty()) return DEFAULT_MAX_RADIUS;
        try {
            Object parsed = Json.parse(body);
            if (parsed instanceof Map<?, ?> m) {
                Object raw = m.get("max_radius");
                if (raw instanceof Number n) return Math.max(1, Math.min(32, n.intValue()));
            }
        } catch (IllegalArgumentException ignored) {}
        return DEFAULT_MAX_RADIUS;
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
