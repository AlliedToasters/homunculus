package dev.toast.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET  /wurst/hud — returns {"visible": <bool>, "wurst_loaded": <bool>}
 * POST /wurst/hud — body {"visible": false} hides Wurst's on-screen HUD
 *                   (logo/version, active-hacks list, TabGui); {"visible": true}
 *                   restores it. Default is hidden so headless recordings stay clean.
 *
 * State is a plain in-memory flag ({@link WurstHud}) read by the render-cancel
 * mixin; no game-state access, so unlike the other /wurst/* handlers this one
 * needs no client-thread hop. The flag is honoured only when Wurst is present —
 * "wurst_loaded": false means the toggle has no visible effect.
 */
public final class WurstHudHandler implements HttpHandler {
    private static final int MAX_BODY_BYTES = 1024;

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method)) {
                respond(exchange, 200, state());
                return;
            }
            if (!"POST".equalsIgnoreCase(method)) {
                respond(exchange, 405, failure("bad_request", "method not allowed"));
                return;
            }
            boolean visible;
            try {
                visible = parseVisible(exchange);
            } catch (IllegalArgumentException e) {
                respond(exchange, 400, failure("bad_request", e.getMessage()));
                return;
            }
            WurstHud.setHidden(!visible);
            respond(exchange, 200, state());
        } finally {
            exchange.close();
        }
    }

    private static boolean parseVisible(HttpExchange exchange) throws IOException {
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
        Object v = m.get("visible");
        if (!(v instanceof Boolean b)) throw new IllegalArgumentException("'visible' must be a boolean");
        return b;
    }

    private static Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("visible", !WurstHud.isHidden());
        m.put("wurst_loaded", Wurst.isApiLoaded());
        return m;
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
