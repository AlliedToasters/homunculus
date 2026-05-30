package dev.toast.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Routes for {@link CodecPassthrough}. Live structured-codec validation
 * (ml.MD §4a, test-ladder step 2).
 *
 * <ul>
 *   <li>POST /codec/passthrough/arm — body {@code {"endpoint": "http://127.0.0.1:25600/codec/roundtrip"}}.
 *       Endpoint is required (no sensible default — the Python server might
 *       be running on a different port for fleet sharing).</li>
 *   <li>POST /codec/passthrough/disarm — drain queue, stop worker, return final counters.</li>
 *   <li>GET /codec/passthrough/status — current state + counters.</li>
 * </ul>
 */
public final class CodecPassthroughHandler implements HttpHandler {

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
                respond(exchange, 200, CodecPassthrough.INSTANCE.disarm());
            } else if (path.endsWith("/status")) {
                if (!"GET".equalsIgnoreCase(method)) {
                    respond(exchange, 405, failure("bad_request", "method not allowed; GET"));
                    return;
                }
                respond(exchange, 200, CodecPassthrough.INSTANCE.snapshot());
            } else {
                respond(exchange, 404, failure("not_found", "unknown codec passthrough route: " + path));
            }
        } finally {
            exchange.close();
        }
    }

    private void handleArm(HttpExchange exchange) throws IOException {
        String endpoint;
        String inferenceUrl = null;
        boolean substitute = false;
        try {
            byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                respond(exchange, 400, failure("bad_request", "body too large"));
                return;
            }
            String body = new String(bytes, StandardCharsets.UTF_8).trim();
            if (body.isEmpty()) {
                respond(exchange, 400, failure("bad_request", "endpoint is required"));
                return;
            }
            Object parsed = Json.parse(body);
            if (!(parsed instanceof Map<?, ?> map)) {
                respond(exchange, 400, failure("bad_request", "body must be a JSON object"));
                return;
            }
            Object v = map.get("endpoint");
            if (!(v instanceof String s) || s.isBlank()) {
                respond(exchange, 400, failure("bad_request", "endpoint must be a non-empty string"));
                return;
            }
            endpoint = s;
            // inference_url is optional — omit to disable neural inference logging.
            Object iv = map.get("inference_url");
            if (iv instanceof String is && !is.isBlank()) {
                inferenceUrl = is;
            }
            // substitute is optional (default false) — when true, the codec
            // server's decoded fields are reconstructed into a packet that
            // goes on the wire instead of the original. All allowlisted
            // SPATIAL_PLAY types are reconstructable; any type without a
            // reconstructor falls back to pass-through.
            Object sv = map.get("substitute");
            if (sv instanceof Boolean sb) {
                substitute = sb;
            }
        } catch (Exception e) {
            respond(exchange, 400, failure("bad_request", "bad json: " + rootMessage(e)));
            return;
        }
        try {
            respond(exchange, 200, CodecPassthrough.INSTANCE.arm(endpoint, inferenceUrl, substitute));
        } catch (Exception e) {
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
