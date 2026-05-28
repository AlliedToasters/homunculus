package dev.toast.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Route for {@link AgentMeta} — control-stack meta-observables for the
 * obs-ablation recorder (neural_interface.md §8f).
 *
 * <ul>
 *   <li>POST /obs/meta — body is a partial meta object, any of
 *       {@code {"g_t": "...", "current_tool": "mine_wood",
 *       "current_tool_args": {...}, "waiting_on_llm": true}}. Only keys
 *       present are updated. Returns the merged state.</li>
 *   <li>GET /obs/meta — current meta state (for diagnostics).</li>
 * </ul>
 *
 * <p>No client-thread dispatch: {@link AgentMeta} is plain synchronized state,
 * so the handler answers directly on the HTTP worker thread. The agent calls
 * this at every turn boundary, so it must be cheap and never block on the
 * render thread.
 */
public final class ObsMetaHandler implements HttpHandler {

    private static final int MAX_BODY_BYTES = 8192;

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method)) {
                respond(exchange, 200, AgentMeta.INSTANCE.snapshot());
                return;
            }
            if (!"POST".equalsIgnoreCase(method)) {
                respond(exchange, 405, failure("bad_request", "method not allowed; POST or GET"));
                return;
            }
            byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                respond(exchange, 400, failure("bad_request", "body too large"));
                return;
            }
            String body = new String(bytes, StandardCharsets.UTF_8).trim();
            if (body.isEmpty()) {
                respond(exchange, 400, failure("bad_request", "body is required"));
                return;
            }
            Object parsed;
            try {
                parsed = Json.parse(body);
            } catch (Exception e) {
                respond(exchange, 400, failure("bad_request", "bad json: " + rootMessage(e)));
                return;
            }
            if (!(parsed instanceof Map<?, ?> map)) {
                respond(exchange, 400, failure("bad_request", "body must be a JSON object"));
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            AgentMeta.INSTANCE.update(typed, PlayerObsSnapshot.currentTick());
            respond(exchange, 200, AgentMeta.INSTANCE.snapshot());
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
