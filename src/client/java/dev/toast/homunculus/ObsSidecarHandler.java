package dev.toast.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Routes for {@link TickSidecarRecorder} — the heavy tick-indexed obs sidecar
 * (neural_interface.md §8e). Independent lifecycle from
 * {@link PacketRecordingHandler}: the frozen-capture runner arms both (the
 * light per-packet stream and this heavy per-tick stream) and joins them by
 * {@code tick}. Kept separate so quick recordings can stay light-only.
 *
 * <ul>
 *   <li>POST /obs/sidecar/arm — body {@code {"path": "/abs/path.sidecar.jsonl"}}
 *       (optional; defaults to {@code ~/.homunculus/recordings/sidecar-<ts>-<port>.jsonl}).</li>
 *   <li>POST /obs/sidecar/disarm — flush, close, return final counters.</li>
 *   <li>GET /obs/sidecar/status — current state + counters.</li>
 * </ul>
 */
public final class ObsSidecarHandler implements HttpHandler {

    private static final int MAX_BODY_BYTES = 4096;

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.endsWith("/arm")) {
                if (!"POST".equalsIgnoreCase(method)) {
                    respond(exchange, 405, failure("bad_request", "method not allowed; POST"));
                    return;
                }
                handleArm(exchange);
            } else if (path.endsWith("/disarm")) {
                if (!"POST".equalsIgnoreCase(method)) {
                    respond(exchange, 405, failure("bad_request", "method not allowed; POST"));
                    return;
                }
                respond(exchange, 200, TickSidecarRecorder.INSTANCE.disarm());
            } else if (path.endsWith("/status")) {
                if (!"GET".equalsIgnoreCase(method)) {
                    respond(exchange, 405, failure("bad_request", "method not allowed; GET"));
                    return;
                }
                respond(exchange, 200, TickSidecarRecorder.INSTANCE.snapshot());
            } else {
                respond(exchange, 404, failure("not_found", "unknown sidecar route: " + path));
            }
        } finally {
            exchange.close();
        }
    }

    private void handleArm(HttpExchange exchange) throws IOException {
        String path = null;
        try {
            byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                respond(exchange, 400, failure("bad_request", "body too large"));
                return;
            }
            String body = new String(bytes, StandardCharsets.UTF_8).trim();
            if (!body.isEmpty()) {
                Object parsed = Json.parse(body);
                if (!(parsed instanceof Map<?, ?> map)) {
                    respond(exchange, 400, failure("bad_request", "body must be a JSON object"));
                    return;
                }
                Object v = map.get("path");
                if (v != null && !(v instanceof String)) {
                    respond(exchange, 400, failure("bad_request", "path must be a string"));
                    return;
                }
                path = (String) v;
            }
        } catch (Exception e) {
            respond(exchange, 400, failure("bad_request", "bad json: " + rootMessage(e)));
            return;
        }
        try {
            respond(exchange, 200, TickSidecarRecorder.INSTANCE.arm(path));
        } catch (IOException e) {
            respond(exchange, 500, failure("internal_error", "arm failed: " + rootMessage(e)));
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
