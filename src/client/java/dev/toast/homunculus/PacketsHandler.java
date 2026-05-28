package dev.toast.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only routes for {@link PacketTap}. Phase 0 of the codec experiment.
 *
 * <ul>
 *   <li>GET /packets/stats — per-class counters + total + uptime.</li>
 *   <li>GET /packets/recent?n=N — last N entries (1..256, default 50).</li>
 * </ul>
 *
 * <p>Both endpoints are diagnostic: presence of counters proves the mixin
 * fired; the recent ring lets us spot-check the packet distribution while a
 * rollout is running. No mutation surface — this is the observe-only phase.
 */
public final class PacketsHandler implements HttpHandler {

    private static final int DEFAULT_N = 50;

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, failure("bad_request", "method not allowed; GET required"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/stats")) {
                respond(exchange, 200, PacketTap.INSTANCE.statsSnapshot());
            } else if (path.endsWith("/recent")) {
                int n;
                try {
                    n = parseN(exchange.getRequestURI());
                } catch (NumberFormatException e) {
                    respond(exchange, 400, failure("bad_request", "n must be an integer"));
                    return;
                }
                respond(exchange, 200, PacketTap.INSTANCE.recentSnapshot(n));
            } else {
                respond(exchange, 404, failure("not_found", "unknown packets route: " + path));
            }
        } finally {
            exchange.close();
        }
    }

    private static int parseN(URI uri) {
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) return DEFAULT_N;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String val = eq < 0 ? "" : pair.substring(eq + 1);
            if ("n".equals(key) && !val.isEmpty()) {
                return Integer.parseInt(val);
            }
        }
        return DEFAULT_N;
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
