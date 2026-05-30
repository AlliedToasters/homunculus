package dev.toast.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST /attack_entity. Body: {"uuid": "<entity-uuid>"} — the uuid comes straight from a
 * /scan_entities snapshot. Melee-attacks that one entity (rotate + attack + swing) and
 * reports whether the hit landed. The injection path for the neural target-selector
 * (neural_interface.md §13.1). Caller positions within reach (≤5 blocks) first.
 */
public final class AttackEntityHandler implements HttpHandler {
    private static final int MAX_BODY_BYTES = 4096;

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, Map.of("error", "method not allowed"));
                return;
            }
            String uuid;
            try {
                uuid = parseUuid(exchange);
            } catch (IllegalArgumentException e) {
                respond(exchange, 400, Map.of("error", "bad request: " + e.getMessage()));
                return;
            }

            Attacker.Result result;
            try {
                result = Attacker.attack(uuid);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                respond(exchange, 500, Map.of("error", "interrupted"));
                return;
            } catch (RuntimeException e) {
                HomunculusClient.LOGGER.error("attack_entity failed", e);
                respond(exchange, 200, failureBody("internal_error", "attack_entity threw: " + rootMessage(e)));
                return;
            }

            if (result instanceof Attacker.Ok ok) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("success", true);
                body.put("uuid", ok.uuid());
                body.put("type", ok.type());
                body.put("distance", ok.distance());
                body.put("health_before", ok.healthBefore());
                body.put("health_after", ok.healthAfter());
                body.put("killed", ok.killed());
                respond(exchange, 200, body);
            } else if (result instanceof Attacker.Failure f) {
                respond(exchange, 200, failureBody(f.reason(), f.message()));
            }
        } finally {
            exchange.close();
        }
    }

    private static String parseUuid(HttpExchange exchange) throws IOException {
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
        Object uuidRaw = m.get("uuid");
        if (!(uuidRaw instanceof String uuid) || uuid.isEmpty()) {
            throw new IllegalArgumentException("'uuid' must be a non-empty string");
        }
        return uuid;
    }

    private static Map<String, Object> failureBody(String reason, String message) {
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
