package dev.toast.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Routes for {@link PacketRoundtrip}. Phase 1 of the codec experiment.
 *
 * <ul>
 *   <li>GET /packets/roundtrip — state snapshot (enabled flag + counters).</li>
 *   <li>POST /packets/roundtrip — body {@code {"enabled": bool}}. Flips the
 *       global kill switch. Reset counters via the body too: {@code
 *       {"reset_counters": true}}.</li>
 * </ul>
 *
 * <p>Single-flag, global kill switch by design: Phase 1 is about whether the
 * substitution loop works, not about per-agent control surfaces. Per-instance
 * control is a Phase 1.1 concern if it ever turns out we need it.
 */
public final class PacketRoundtripHandler implements HttpHandler {

    private static final int MAX_BODY_BYTES = 256;

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method)) {
                respond(exchange, 200, PacketRoundtrip.INSTANCE.snapshot());
                return;
            }
            if (!"POST".equalsIgnoreCase(method)) {
                respond(exchange, 405, failure("bad_request", "method not allowed; GET or POST"));
                return;
            }
            Object parsed;
            try {
                byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
                if (bytes.length > MAX_BODY_BYTES) {
                    respond(exchange, 400, failure("bad_request", "body too large"));
                    return;
                }
                String body = new String(bytes, StandardCharsets.UTF_8).trim();
                if (body.isEmpty()) {
                    respond(exchange, 400, failure("bad_request", "empty body"));
                    return;
                }
                parsed = Json.parse(body);
            } catch (Exception e) {
                respond(exchange, 400, failure("bad_request", "bad json: " + rootMessage(e)));
                return;
            }
            if (!(parsed instanceof Map<?, ?> map)) {
                respond(exchange, 400, failure("bad_request", "body must be a JSON object"));
                return;
            }
            if (map.get("reset_counters") instanceof Boolean b && b) {
                PacketRoundtrip.INSTANCE.resetCounters();
            }
            if (map.get("enabled") instanceof Boolean en) {
                PacketRoundtrip.INSTANCE.setEnabled(en);
            }
            respond(exchange, 200, PacketRoundtrip.INSTANCE.snapshot());
        } finally {
            exchange.close();
        }
    }

    private static Map<String, Object> failure(String reason, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("reason", reason);
        m.put("message", message);
        return m;
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
