package dev.toast.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * POST /wurst/hack — body {"name": "KillAura", "enabled": true}
 *
 * Toggles a Wurst module via the reflection bridge. Setting enabled=true on a
 * hack that's already on is a no-op (and reports changed=false). Toggle runs on
 * the client thread because Wurst's setEnabled() fires event-listener callbacks
 * that touch game state.
 */
public final class WurstHackHandler implements HttpHandler {
    private static final long TIMEOUT_MS = 2000;
    private static final int MAX_BODY_BYTES = 1024;

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, failure("bad_request", "method not allowed"));
                return;
            }
            if (!Wurst.isApiLoaded()) {
                respond(exchange, 200, failure("wurst_not_loaded",
                        "Wurst Client not present at runtime"));
                return;
            }

            Request req;
            try {
                req = parseBody(exchange);
            } catch (IllegalArgumentException e) {
                respond(exchange, 400, failure("bad_request", e.getMessage()));
                return;
            }

            Map<String, Object> body;
            try {
                final Request fr = req;
                body = ClientThread.supply(() -> doToggle(fr.name, fr.enabled))
                        .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                respond(exchange, 504, failure("internal_error", "client thread timeout"));
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                respond(exchange, 500, failure("internal_error", "interrupted"));
                return;
            } catch (ExecutionException e) {
                HomunculusClient.LOGGER.error("Wurst toggle failed", e.getCause());
                respond(exchange, 500, failure("internal_error", "Wurst toggle threw"));
                return;
            }
            respond(exchange, 200, body);
        } finally {
            exchange.close();
        }
    }

    private record Request(String name, boolean enabled) {}

    private static Request parseBody(HttpExchange exchange) throws IOException {
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
        Object nameRaw = m.get("name");
        if (!(nameRaw instanceof String name) || name.isEmpty()) {
            throw new IllegalArgumentException("'name' must be a non-empty string");
        }
        Object enabledRaw = m.get("enabled");
        if (!(enabledRaw instanceof Boolean enabled)) {
            throw new IllegalArgumentException("'enabled' must be a boolean");
        }
        return new Request(name, enabled);
    }

    private static Map<String, Object> doToggle(String name, boolean enabled) {
        Object hack = Wurst.findHack(name);
        if (hack == null) {
            return failure("hack_not_found",
                    "no Wurst hack named '" + name + "' (case-insensitive). Try GET /wurst/status to list available hacks.");
        }
        boolean before = Wurst.isEnabled(hack);
        if (before != enabled) {
            Wurst.setEnabled(hack, enabled);
        }
        boolean after = Wurst.isEnabled(hack);
        String resolvedName = Wurst.getName(hack);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", after == enabled);
        m.put("name", resolvedName);
        m.put("enabled", after);
        m.put("changed", before != after);
        if (after != enabled) {
            m.put("reason", "toggle_rejected");
            m.put("message", "Wurst did not accept the toggle (likely missing prerequisites — e.g. some hacks refuse to enable outside a world).");
        }
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
