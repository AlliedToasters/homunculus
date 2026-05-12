package dev.klear.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GotoHandler implements HttpHandler {
    private static final long DEFAULT_TIMEOUT_SECONDS = 60;
    private static final long DEFAULT_TOLERANCE = 2;
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

            Goto.Outcome outcome = Goto.run(req.x, req.y, req.z, req.timeoutSeconds, req.arrivalTolerance);
            respond(exchange, 200, toBody(req, outcome));
        } finally {
            exchange.close();
        }
    }

    private static Map<String, Object> toBody(Request req, Goto.Outcome outcome) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (outcome instanceof Goto.Arrived a) {
            body.put("success", true);
            body.put("reason", "arrived");
            body.put("target", List.of(req.x, req.y, req.z));
            body.put("final_position", positionList(a.finalPosition()));
            body.put("message", a.message());
        } else if (outcome instanceof Goto.Failed f) {
            body.put("success", false);
            body.put("reason", f.reason());
            body.put("target", List.of(req.x, req.y, req.z));
            body.put("final_position", positionList(f.finalPosition()));
            body.put("message", f.message());
        }
        return body;
    }

    private static Object positionList(double[] pos) {
        if (pos == null) return null;
        return List.of(pos[0], pos[1], pos[2]);
    }

    private record Request(int x, int y, int z, long timeoutSeconds, long arrivalTolerance) {}

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

        int x = requireInt(m, "x");
        int y = requireInt(m, "y");
        int z = requireInt(m, "z");

        long timeoutSec = DEFAULT_TIMEOUT_SECONDS;
        Object timeoutRaw = m.get("timeout_seconds");
        if (timeoutRaw instanceof Number tn) {
            long t = tn.longValue();
            if (t < 1) throw new IllegalArgumentException("'timeout_seconds' must be >= 1");
            timeoutSec = t;
        } else if (timeoutRaw != null) {
            throw new IllegalArgumentException("'timeout_seconds' must be a number");
        }

        long tolerance = DEFAULT_TOLERANCE;
        Object tolRaw = m.get("arrival_tolerance");
        if (tolRaw instanceof Number tn) {
            long t = tn.longValue();
            if (t < 0) throw new IllegalArgumentException("'arrival_tolerance' must be >= 0");
            tolerance = t;
        } else if (tolRaw != null) {
            throw new IllegalArgumentException("'arrival_tolerance' must be a number");
        }

        return new Request(x, y, z, timeoutSec, tolerance);
    }

    private static int requireInt(Map<?, ?> m, String key) {
        Object raw = m.get(key);
        if (!(raw instanceof Number n)) throw new IllegalArgumentException("'" + key + "' must be an integer");
        if (n.doubleValue() != n.longValue()) throw new IllegalArgumentException("'" + key + "' must be an integer (not a float)");
        long v = n.longValue();
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) throw new IllegalArgumentException("'" + key + "' out of int range");
        return (int) v;
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
