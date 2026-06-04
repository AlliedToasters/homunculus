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
 * Read-only routes for {@link PacketTap} (outbound actions) and
 * {@link ServerFeedbackTap} (inbound corrective feedback).
 *
 * <ul>
 *   <li>GET /packets/stats — outbound per-class counters + total + uptime.</li>
 *   <li>GET /packets/recent?n=N — last N outbound entries (1..256, default 50).</li>
 *   <li>GET /packets/feedback — inbound corrective counters (rubberbands,
 *       motion overrides) + total + uptime. The live rubber-band rate.</li>
 *   <li>GET /packets/feedback/recent?n=N — last N corrective entries.</li>
 * </ul>
 *
 * <p>All endpoints are diagnostic: presence of counters proves the mixins
 * fired; the recent rings let us spot-check distributions while a rollout is
 * running. No mutation surface — observe-only.
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
            boolean feedback = path.contains("/feedback");
            if (path.endsWith("/recent")) {
                int n;
                try {
                    n = parseN(exchange.getRequestURI());
                } catch (NumberFormatException e) {
                    respond(exchange, 400, failure("bad_request", "n must be an integer"));
                    return;
                }
                respond(exchange, 200, feedback
                        ? ServerFeedbackTap.INSTANCE.recentSnapshot(n)
                        : PacketTap.INSTANCE.recentSnapshot(n));
            } else if (path.endsWith("/feedback")) {
                respond(exchange, 200, ServerFeedbackTap.INSTANCE.statsSnapshot());
            } else if (path.endsWith("/stats")) {
                respond(exchange, 200, PacketTap.INSTANCE.statsSnapshot());
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
