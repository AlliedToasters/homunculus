package dev.klear.homunculus;

import baritone.api.BaritoneAPI;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * GET/POST /baritone/allow_break.
 *
 * <p>Surfaces Baritone's {@code allowBreak} setting — whether the pathfinder is
 * allowed to plan block-breaking actions while pathing. When false, the pathfinder
 * is forced to find break-free routes; relevant for build operations where we
 * don't want Baritone to break partially-built walls/ceilings to take a shortcut.
 *
 * <p>GET returns {@code {success, value: true|false}}.
 * POST replaces. Body: {@code {"value": true|false}}.
 *
 * <p>Persistent in the Baritone singleton until another caller writes it. No
 * built-in auto-restore — callers are responsible for restoring after their op.
 */
public final class AllowBreakHandler implements HttpHandler {

    private static final long GAME_THREAD_TIMEOUT_MS = 5_000;
    private static final int MAX_BODY_BYTES = 256;

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method)) {
                handleGet(exchange);
            } else if ("POST".equalsIgnoreCase(method)) {
                handlePost(exchange);
            } else {
                respond(exchange, 405, Map.of("error", "method not allowed"));
            }
        } finally {
            exchange.close();
        }
    }

    private void handleGet(HttpExchange exchange) throws IOException {
        if (!Baritone.isApiLoaded()) {
            respond(exchange, 200, failureBody("baritone_not_loaded", "Baritone API not present"));
            return;
        }
        Boolean cur;
        try {
            cur = ClientThread.supply(() -> BaritoneAPI.getSettings().allowBreak.value)
                    .get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            respond(exchange, 200, failureBody("internal_error", "read failed: " + rootMessage(e)));
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("value", cur);
        respond(exchange, 200, body);
    }

    private void handlePost(HttpExchange exchange) throws IOException {
        if (!Baritone.isApiLoaded()) {
            respond(exchange, 200, failureBody("baritone_not_loaded", "Baritone API not present"));
            return;
        }
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
        Object raw = map.get("value");
        if (!(raw instanceof Boolean value)) {
            respond(exchange, 400, Map.of("error", "missing or non-boolean 'value'"));
            return;
        }

        try {
            ClientThread.supply(() -> {
                BaritoneAPI.getSettings().allowBreak.value = value;
                return null;
            }).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            respond(exchange, 200, failureBody("internal_error", "write failed: " + rootMessage(e)));
            return;
        }

        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("success", true);
        ok.put("value", value);
        respond(exchange, 200, ok);
    }

    private static Map<String, Object> failureBody(String reason, String message) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("success", false);
        b.put("reason", reason);
        b.put("message", message);
        return b;
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
