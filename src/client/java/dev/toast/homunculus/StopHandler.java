package dev.toast.homunculus;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class StopHandler implements HttpHandler {
    private static final long GAME_THREAD_TIMEOUT_MS = 5_000;

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, Map.of("error", "method not allowed"));
                return;
            }

            if (!Baritone.isApiLoaded()) {
                respond(exchange, 200, failure("baritone_not_loaded",
                        "Baritone API not present at runtime"));
                return;
            }

            // Deliberately bypasses Baritone.SESSION_LOCK so /baritone/stop can interrupt
            // an in-flight /baritone/mine or /baritone/goto.
            // cancelEverything() returns boolean but it doesn't mean "anything was canceled" —
            // empirically returns true even when idle. Sample the state ourselves first.
            boolean acked;
            try {
                acked = ClientThread.supply(() -> {
                    IBaritone bar = BaritoneAPI.getProvider().getPrimaryBaritone();
                    if (bar == null) throw new IllegalStateException("no primary Baritone (player not in world?)");
                    boolean wasActive = bar.getPathingBehavior().isPathing()
                            || bar.getCustomGoalProcess().isActive();
                    bar.getPathingBehavior().cancelEverything();
                    return wasActive;
                }).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                respond(exchange, 500, failure("internal_error",
                        "cancelEverything threw: " + rootMessage(e)));
                return;
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("acked", acked);
            body.put("message", acked
                    ? "Baritone canceled (was running)"
                    : "Baritone idle; nothing to cancel");
            respond(exchange, 200, body);
        } finally {
            exchange.close();
        }
    }

    private static Map<String, Object> failure(String reason, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("reason", reason);
        body.put("message", message);
        return body;
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
