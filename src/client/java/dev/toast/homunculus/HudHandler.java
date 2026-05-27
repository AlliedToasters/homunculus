package dev.toast.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET  /hud — returns {"success": true, "visible": {"health": true, ...}}
 * POST /hud — body maps element keys to desired visibility, e.g.
 *             {"health": false, "hotbar": false}. Optional "all": &lt;bool&gt;
 *             sets every element first; explicit keys then override. Unlisted
 *             elements are left unchanged. Returns the full visibility map.
 *
 * <p>Toggles vanilla in-game HUD elements (health, food, air, hotbar, effects,
 * experience, crosshair, selected-item name) by cancelling their render in
 * {@code GuiHudMixin}. State is plain in-memory flags ({@link HudState}) read on
 * the render thread, so — unlike the other state-touching handlers — no
 * client-thread hop is needed. All elements default to visible; this is opt-in
 * suppression for clean recordings (see [[project-agent-video-recording]]).
 */
public final class HudHandler implements HttpHandler {
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
            try {
                applyBody(exchange);
            } catch (IllegalArgumentException e) {
                respond(exchange, 400, failure("bad_request", e.getMessage()));
                return;
            }
            respond(exchange, 200, state());
        } finally {
            exchange.close();
        }
    }

    private static void applyBody(HttpExchange exchange) throws IOException {
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
        if (m.isEmpty()) throw new IllegalArgumentException("no fields; expected element keys or 'all'");

        // Validate the whole body before mutating anything, so a typo can't leave
        // the HUD half-applied.
        Boolean allVisible = null;
        List<Map.Entry<HudState.Element, Boolean>> ops = new ArrayList<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (!(e.getValue() instanceof Boolean b)) {
                throw new IllegalArgumentException("'" + key + "' must be a boolean");
            }
            if ("all".equals(key)) {
                allVisible = b;
                continue;
            }
            HudState.Element el = HudState.byKey(key);
            if (el == null) {
                throw new IllegalArgumentException("unknown element '" + key + "'; valid: " + validKeys());
            }
            ops.add(Map.entry(el, b));
        }

        if (allVisible != null) {
            boolean hidden = !allVisible;
            for (HudState.Element el : HudState.Element.values()) HudState.setHidden(el, hidden);
        }
        for (Map.Entry<HudState.Element, Boolean> op : ops) {
            HudState.setHidden(op.getKey(), !op.getValue());
        }
    }

    private static String validKeys() {
        List<String> keys = new ArrayList<>();
        keys.add("all");
        for (HudState.Element e : HudState.Element.values()) keys.add(e.key);
        return String.join(", ", keys);
    }

    private static Map<String, Object> state() {
        Map<String, Object> visible = new LinkedHashMap<>();
        for (HudState.Element e : HudState.Element.values()) {
            visible.put(e.key, !HudState.isHidden(e));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("visible", visible);
        return body;
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
