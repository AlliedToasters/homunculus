package dev.klear.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes for {@link Evasion}, mounted at {@code /evasion/arm}, {@code /evasion/disarm},
 * {@code /evasion/status}. One class handles all three because they're tiny and share
 * the same response envelope.
 *
 * <ul>
 *   <li>POST /evasion/arm — body {@code {"x":..,"y":..,"z":..}}. Captures the anchor and
 *       enables the hostile-mob watcher. Re-arming replaces the prior anchor.</li>
 *   <li>POST /evasion/disarm — clears state. Does NOT cancel an in-progress flee — the
 *       player keeps walking; whatever next baritone task fires will override the path.</li>
 *   <li>GET /evasion/status — returns
 *       {@code {success, armed, fired, anchor, attackers, flee_state, flee_failure_reason}}.</li>
 * </ul>
 */
public final class EvasionHandler implements HttpHandler {

    private static final int MAX_BODY_BYTES = 256;

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.endsWith("/arm")) {
                if (!"POST".equalsIgnoreCase(method)) {
                    respond(exchange, 405, Map.of("error", "method not allowed; POST required"));
                    return;
                }
                handleArm(exchange);
            } else if (path.endsWith("/disarm")) {
                if (!"POST".equalsIgnoreCase(method)) {
                    respond(exchange, 405, Map.of("error", "method not allowed; POST required"));
                    return;
                }
                handleDisarm(exchange);
            } else if (path.endsWith("/status")) {
                if (!"GET".equalsIgnoreCase(method)) {
                    respond(exchange, 405, Map.of("error", "method not allowed; GET required"));
                    return;
                }
                handleStatus(exchange);
            } else {
                respond(exchange, 404, Map.of("error", "unknown evasion route: " + path));
            }
        } finally {
            exchange.close();
        }
    }

    private void handleArm(HttpExchange exchange) throws IOException {
        Object parsed;
        try {
            byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                respond(exchange, 400, Map.of("error", "body too large"));
                return;
            }
            String body = new String(bytes, StandardCharsets.UTF_8).trim();
            if (body.isEmpty()) {
                respond(exchange, 400, Map.of("error", "empty body"));
                return;
            }
            parsed = Json.parse(body);
        } catch (Exception e) {
            respond(exchange, 400, Map.of("error", "bad json: " + rootMessage(e)));
            return;
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            respond(exchange, 400, Map.of("error", "body must be a JSON object"));
            return;
        }
        Double x = readNumber(map.get("x"));
        Double y = readNumber(map.get("y"));
        Double z = readNumber(map.get("z"));
        if (x == null || y == null || z == null) {
            respond(exchange, 400, Map.of("error", "missing or non-numeric x/y/z"));
            return;
        }
        Evasion.INSTANCE.arm(new double[] { x, y, z });
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("success", true);
        ok.put("anchor", List.of(x, y, z));
        respond(exchange, 200, ok);
    }

    private void handleDisarm(HttpExchange exchange) throws IOException {
        Evasion.INSTANCE.disarm();
        respond(exchange, 200, Map.of("success", true));
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        Evasion.Snapshot s = Evasion.INSTANCE.snapshot();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("armed", s.armed());
        body.put("fired", s.fired());
        if (s.anchor() != null) {
            double[] a = s.anchor();
            body.put("anchor", List.of(a[0], a[1], a[2]));
        } else {
            body.put("anchor", null);
        }
        body.put("attackers", s.attackers());
        body.put("flee_state", s.fleeState().name().toLowerCase());
        if (s.fleeFailureReason() != null) {
            body.put("flee_failure_reason", s.fleeFailureReason());
        }
        respond(exchange, 200, body);
    }

    private static Double readNumber(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return null;
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
