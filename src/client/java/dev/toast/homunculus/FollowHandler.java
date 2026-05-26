package dev.toast.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.world.entity.EntityType;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * POST /baritone/follow
 *   {"follow_types": ["cow","pig",...], "pickup": true, "duration_seconds": 12, "follow_radius": 2}
 *
 * Drives {@link Follow} (Baritone FollowProcess) to persistently pursue the nearest entity of the
 * given types and (when pickup=true) collect dropped items. Blocks for the duration (or until
 * nothing matches), then cancels. Mirrors {@link GotoHandler}'s parse/respond conventions.
 */
public final class FollowHandler implements HttpHandler {
    private static final long DEFAULT_DURATION_SECONDS = 12;
    private static final int DEFAULT_FOLLOW_RADIUS = 2;
    private static final boolean DEFAULT_PICKUP = true;
    private static final int MAX_BODY_BYTES = 4096;

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, Map.of("success", false, "reason", "bad_request",
                        "message", "method not allowed"));
                return;
            }
            Request req;
            try {
                req = parseBody(exchange);
            } catch (IllegalArgumentException e) {
                respond(exchange, 400, Map.of("success", false, "reason", "bad_request",
                        "message", e.getMessage()));
                return;
            }
            Follow.Result r = Follow.run(req.types, req.pickup, req.durationSeconds, req.followRadius);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", r.ok());
            body.put("reason", r.reason());
            body.put("message", r.message());
            body.put("follow_types", new ArrayList<>(req.typeIds));
            body.put("pickup", req.pickup);
            respond(exchange, 200, body);
        } finally {
            exchange.close();
        }
    }

    private record Request(Set<EntityType<?>> types, List<String> typeIds, boolean pickup,
                           long durationSeconds, int followRadius) {}

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

        Object ftRaw = m.get("follow_types");
        if (!(ftRaw instanceof List<?> rawList) || rawList.isEmpty()) {
            throw new IllegalArgumentException("'follow_types' must be a non-empty array of entity ids");
        }
        Set<EntityType<?>> types = new HashSet<>();
        List<String> ids = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (Object o : rawList) {
            if (!(o instanceof String s)) {
                throw new IllegalArgumentException("'follow_types' entries must be strings");
            }
            Optional<EntityType<?>> et = EntityType.byString(s);
            if (et.isEmpty()) {
                unknown.add(s);
                continue;
            }
            types.add(et.get());
            ids.add(s);
        }
        if (types.isEmpty()) {
            throw new IllegalArgumentException("no valid entity types in 'follow_types' (unknown: " + unknown + ")");
        }

        boolean pickup = DEFAULT_PICKUP;
        Object pRaw = m.get("pickup");
        if (pRaw instanceof Boolean b) pickup = b;
        else if (pRaw != null) throw new IllegalArgumentException("'pickup' must be a boolean");

        long dur = DEFAULT_DURATION_SECONDS;
        Object dRaw = m.get("duration_seconds");
        if (dRaw instanceof Number n) {
            long d = n.longValue();
            if (d < 1) throw new IllegalArgumentException("'duration_seconds' must be >= 1");
            dur = d;
        } else if (dRaw != null) {
            throw new IllegalArgumentException("'duration_seconds' must be a number");
        }

        int radius = DEFAULT_FOLLOW_RADIUS;
        Object rRaw = m.get("follow_radius");
        if (rRaw instanceof Number n) {
            int rr = n.intValue();
            if (rr < 1) throw new IllegalArgumentException("'follow_radius' must be >= 1");
            radius = rr;
        } else if (rRaw != null) {
            throw new IllegalArgumentException("'follow_radius' must be a number");
        }

        return new Request(types, ids, pickup, dur, radius);
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
