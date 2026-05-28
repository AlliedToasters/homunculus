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
 * Routes for {@link WaterAversion}. Same shape as {@link EvasionHandler} —
 * {@code /water_aversion/arm}, {@code /water_aversion/disarm},
 * {@code /water_aversion/status}.
 *
 * <p>Unlike evasion, arm takes no body — the dry-land target is computed at
 * fire-time, not supplied by the caller.
 *
 * <ul>
 *   <li>POST /water_aversion/arm — empty body. Enables the watcher.</li>
 *   <li>POST /water_aversion/disarm — clears state. Does NOT cancel an
 *       in-progress flee.</li>
 *   <li>GET /water_aversion/status — returns
 *       {@code {success, armed, fired, submerged_pos, dry_land_pos,
 *       flee_state, flee_failure_reason}}.</li>
 * </ul>
 */
public final class WaterAversionHandler implements HttpHandler {

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
                WaterAversion.INSTANCE.arm();
                respond(exchange, 200, Map.of("success", true));
            } else if (path.endsWith("/disarm")) {
                if (!"POST".equalsIgnoreCase(method)) {
                    respond(exchange, 405, Map.of("error", "method not allowed; POST required"));
                    return;
                }
                WaterAversion.INSTANCE.disarm();
                respond(exchange, 200, Map.of("success", true));
            } else if (path.endsWith("/status")) {
                if (!"GET".equalsIgnoreCase(method)) {
                    respond(exchange, 405, Map.of("error", "method not allowed; GET required"));
                    return;
                }
                handleStatus(exchange);
            } else {
                respond(exchange, 404, Map.of("error", "unknown water_aversion route: " + path));
            }
        } finally {
            exchange.close();
        }
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        WaterAversion.Snapshot s = WaterAversion.INSTANCE.snapshot();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("armed", s.armed());
        body.put("fired", s.fired());
        body.put("fired_at_ms", s.firedAtMs() == 0L ? null : s.firedAtMs());
        if (s.submergedPos() != null) {
            double[] p = s.submergedPos();
            body.put("submerged_pos", List.of(p[0], p[1], p[2]));
        } else {
            body.put("submerged_pos", null);
        }
        if (s.dryLandPos() != null) {
            int[] p = s.dryLandPos();
            body.put("dry_land_pos", List.of(p[0], p[1], p[2]));
        } else {
            body.put("dry_land_pos", null);
        }
        body.put("flee_state", s.fleeState().name().toLowerCase());
        if (s.fleeFailureReason() != null) {
            body.put("flee_failure_reason", s.fleeFailureReason());
        }
        respond(exchange, 200, body);
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
