package dev.toast.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** POST /bed/place. Body: {"item": "minecraft:red_bed"} (item optional; any *_bed if omitted). */
public final class BedPlaceHandler implements HttpHandler {
    private static final int MAX_BODY_BYTES = 4096;

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, Map.of("error", "method not allowed"));
                return;
            }

            String itemId = parseOptionalItem(exchange);

            BedPlacer.Result result;
            try {
                result = BedPlacer.place(itemId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                respond(exchange, 500, Map.of("error", "interrupted"));
                return;
            } catch (RuntimeException e) {
                HomunculusClient.LOGGER.error("bed/place failed", e);
                respond(exchange, 200, failureBody("internal_error", "bed/place threw: " + rootMessage(e)));
                return;
            }

            if (result instanceof BedPlacer.Ok ok) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("success", true);
                body.put("foot", List.of(ok.fx(), ok.fy(), ok.fz()));
                body.put("head", List.of(ok.hx(), ok.hy(), ok.hz()));
                body.put("color", ok.color());
                body.put("facing", ok.facing());
                respond(exchange, 200, body);
            } else if (result instanceof BedPlacer.Failure f) {
                respond(exchange, 200, failureBody(f.reason(), f.message()));
            }
        } finally {
            exchange.close();
        }
    }

    /** Returns null if "item" key is absent or body is empty/invalid. */
    private static String parseOptionalItem(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) return null;
        String body = new String(bytes, StandardCharsets.UTF_8).trim();
        if (body.isEmpty()) return null;
        try {
            Object parsed = Json.parse(body);
            if (parsed instanceof Map<?, ?> m) {
                Object raw = m.get("item");
                if (raw instanceof String s && !s.isBlank()) return s;
            }
        } catch (IllegalArgumentException ignored) {}
        return null;
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
