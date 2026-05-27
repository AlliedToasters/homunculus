package dev.toast.homunculus;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * GET/POST /baritone/render — toggles Baritone's in-world render overlay
 * (path lines, goal box/beacon, targeted-block selection boxes) as one group.
 *
 * <p>Baritone 1.21.4/1.13.1 has no single {@code renderOverlay} master switch,
 * so this flips the on-by-default visual settings together: {@code renderPath},
 * {@code renderGoal}, {@code renderGoalXZBeacon}, {@code renderSelectionBoxes},
 * {@code renderSelection}. The pathfinder still runs; only the visuals change —
 * useful for clean headless recordings without losing the overlay as a debugging
 * tool (see [[project-agent-video-recording]]).
 *
 * <p>GET returns {@code {success, visible, settings:{name:bool,...}}}.
 * POST replaces. Body: {@code {"visible": true|false}}.
 *
 * <p>Unlike Wurst's HUD toggle this does not force a clean state at startup —
 * Baritone's shipped defaults stand until a caller writes here. Persistent in the
 * Baritone singleton; no auto-restore.
 */
public final class BaritoneRenderHandler implements HttpHandler {

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
        Map<String, Object> settings;
        try {
            settings = ClientThread.supply(BaritoneRenderHandler::readSettings)
                    .get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            respond(exchange, 200, failureBody("internal_error", "read failed: " + rootMessage(e)));
            return;
        }
        respond(exchange, 200, stateBody(settings));
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
        Object raw = map.get("visible");
        if (!(raw instanceof Boolean visible)) {
            respond(exchange, 400, Map.of("error", "missing or non-boolean 'visible'"));
            return;
        }

        Map<String, Object> settings;
        try {
            settings = ClientThread.supply(() -> {
                applySettings(visible);
                return readSettings();
            }).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            respond(exchange, 200, failureBody("internal_error", "write failed: " + rootMessage(e)));
            return;
        }
        respond(exchange, 200, stateBody(settings));
    }

    private static void applySettings(boolean visible) {
        Settings s = BaritoneAPI.getSettings();
        s.renderPath.value = visible;
        s.renderGoal.value = visible;
        s.renderGoalXZBeacon.value = visible;
        s.renderSelectionBoxes.value = visible;
        s.renderSelection.value = visible;
    }

    private static Map<String, Object> readSettings() {
        Settings s = BaritoneAPI.getSettings();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("renderPath", s.renderPath.value);
        m.put("renderGoal", s.renderGoal.value);
        m.put("renderGoalXZBeacon", s.renderGoalXZBeacon.value);
        m.put("renderSelectionBoxes", s.renderSelectionBoxes.value);
        m.put("renderSelection", s.renderSelection.value);
        return m;
    }

    private static Map<String, Object> stateBody(Map<String, Object> settings) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        // Summary flag: true if the primary path overlay is on.
        body.put("visible", Boolean.TRUE.equals(settings.get("renderPath")));
        body.put("settings", settings);
        return body;
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
