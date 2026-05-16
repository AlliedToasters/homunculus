package dev.klear.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * GET /wurst/status — lists every Wurst hack with current enabled state +
 * category. Lets the rollout harness automate its pre-flight checklist
 * (assert KillAura/AutoEat/AutoTool/... are on before starting).
 *
 * Response shape:
 *   {"success": true, "count": 140, "enabled_count": 5,
 *    "hacks": [{"name": "AutoEat", "enabled": true, "category": "ITEMS"}, ...]}
 */
public final class WurstStatusHandler implements HttpHandler {
    private static final long TIMEOUT_MS = 2000;

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, failure("bad_request", "method not allowed"));
                return;
            }
            if (!Wurst.isApiLoaded()) {
                respond(exchange, 200, failure("wurst_not_loaded",
                        "Wurst Client not present at runtime"));
                return;
            }

            Map<String, Object> body;
            try {
                body = ClientThread.supply(WurstStatusHandler::snapshot)
                        .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                respond(exchange, 504, failure("internal_error", "client thread timeout"));
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                respond(exchange, 500, failure("internal_error", "interrupted"));
                return;
            } catch (ExecutionException e) {
                HomunculusClient.LOGGER.error("Wurst status snapshot failed", e.getCause());
                respond(exchange, 500, failure("internal_error", "Wurst status threw"));
                return;
            }
            respond(exchange, 200, body);
        } finally {
            exchange.close();
        }
    }

    private static Map<String, Object> snapshot() {
        List<Object> hacks = Wurst.getAllHacks();
        List<Map<String, Object>> entries = new ArrayList<>(hacks.size());
        long enabledCount = 0;
        for (Object hack : hacks) {
            Map<String, Object> e = new LinkedHashMap<>();
            String name = Wurst.getName(hack);
            boolean enabled = Wurst.isEnabled(hack);
            e.put("name", name);
            e.put("enabled", enabled);
            String cat = Wurst.getCategory(hack);
            if (cat != null) e.put("category", cat);
            entries.add(e);
            if (enabled) enabledCount++;
        }
        entries.sort(Comparator.comparing(m -> (String) m.get("name")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("count", entries.size());
        body.put("enabled_count", enabledCount);
        body.put("hacks", entries);
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
