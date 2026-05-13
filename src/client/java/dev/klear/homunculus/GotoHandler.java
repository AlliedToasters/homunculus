package dev.klear.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GotoHandler implements HttpHandler {
    private static final long DEFAULT_TIMEOUT_SECONDS = 60;
    private static final long DEFAULT_TOLERANCE = 2;
    private static final int MAX_BODY_BYTES = 4096;

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, Map.of("error", "method not allowed"));
                return;
            }

            Request req;
            try {
                req = parseBody(exchange);
            } catch (IllegalArgumentException e) {
                respond(exchange, 400, Map.of("error", "bad request: " + e.getMessage()));
                return;
            }

            Goto.Options opts = new Goto.Options(req.allowPlace, req.throwawayItems, req.ensureThrowawayInHotbar);
            Goto.Outcome outcome = Goto.run(
                    req.goalType, req.x, req.y, req.z, req.timeoutSeconds, req.arrivalTolerance, opts);
            respond(exchange, 200, toBody(req, outcome));
        } finally {
            exchange.close();
        }
    }

    private static Map<String, Object> toBody(Request req, Goto.Outcome outcome) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (outcome instanceof Goto.Arrived a) {
            body.put("success", true);
            body.put("reason", "arrived");
            body.put("target", targetList(req));
            body.put("final_position", positionList(a.finalPosition()));
            body.put("message", a.message());
        } else if (outcome instanceof Goto.Failed f) {
            body.put("success", false);
            body.put("reason", f.reason());
            body.put("target", targetList(req));
            body.put("final_position", positionList(f.finalPosition()));
            body.put("message", f.message());
        }
        return body;
    }

    private static Object targetList(Request req) {
        return switch (req.goalType) {
            case BLOCK -> List.of(req.x, req.y, req.z);
            case Y_LEVEL -> Map.of("y", req.y);
        };
    }

    private static Object positionList(double[] pos) {
        if (pos == null) return null;
        return List.of(pos[0], pos[1], pos[2]);
    }

    private record Request(
            Goto.GoalType goalType,
            int x, int y, int z,
            long timeoutSeconds,
            long arrivalTolerance,
            boolean allowPlace,
            List<String> throwawayItems,
            boolean ensureThrowawayInHotbar) {}

    private static Request parseBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) throw new IllegalArgumentException("body too large");
        String body = new String(bytes, StandardCharsets.UTF_8).trim();
        if (body.isEmpty()) throw new IllegalArgumentException("empty body");
        Object parsedJson;
        try {
            parsedJson = Json.parse(body);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid JSON (" + e.getMessage() + ")");
        }
        if (!(parsedJson instanceof Map<?, ?> m)) throw new IllegalArgumentException("expected JSON object");

        Goto.GoalType goalType = Goto.GoalType.BLOCK;
        Object gtRaw = m.get("goal_type");
        if (gtRaw instanceof String s) {
            switch (s) {
                case "block" -> goalType = Goto.GoalType.BLOCK;
                case "y_level" -> goalType = Goto.GoalType.Y_LEVEL;
                default -> throw new IllegalArgumentException(
                        "'goal_type' must be \"block\" or \"y_level\" (got \"" + s + "\")");
            }
        } else if (gtRaw != null) {
            throw new IllegalArgumentException("'goal_type' must be a string");
        }

        int x = 0, y, z = 0;
        switch (goalType) {
            case BLOCK -> {
                x = requireInt(m, "x");
                y = requireInt(m, "y");
                z = requireInt(m, "z");
            }
            case Y_LEVEL -> {
                y = requireInt(m, "y");
                // x and z are accepted but unused — tolerate them silently for client schema uniformity.
                if (m.get("x") != null) x = requireInt(m, "x");
                if (m.get("z") != null) z = requireInt(m, "z");
            }
            default -> throw new IllegalStateException("unreachable");
        }

        long timeoutSec = DEFAULT_TIMEOUT_SECONDS;
        Object timeoutRaw = m.get("timeout_seconds");
        if (timeoutRaw instanceof Number tn) {
            long t = tn.longValue();
            if (t < 1) throw new IllegalArgumentException("'timeout_seconds' must be >= 1");
            timeoutSec = t;
        } else if (timeoutRaw != null) {
            throw new IllegalArgumentException("'timeout_seconds' must be a number");
        }

        long tolerance = DEFAULT_TOLERANCE;
        Object tolRaw = m.get("arrival_tolerance");
        if (tolRaw instanceof Number tn) {
            long t = tn.longValue();
            if (t < 0) throw new IllegalArgumentException("'arrival_tolerance' must be >= 0");
            tolerance = t;
        } else if (tolRaw != null) {
            throw new IllegalArgumentException("'arrival_tolerance' must be a number");
        }

        boolean allowPlace = true;
        Object apRaw = m.get("allow_place");
        if (apRaw instanceof Boolean b) {
            allowPlace = b;
        } else if (apRaw != null) {
            throw new IllegalArgumentException("'allow_place' must be a boolean");
        }

        List<String> throwawayItems = null;
        Object tiRaw = m.get("throwaway_items");
        if (tiRaw instanceof List<?> rawList) {
            List<String> parsed = new ArrayList<>(rawList.size());
            for (Object o : rawList) {
                if (!(o instanceof String s)) {
                    throw new IllegalArgumentException("'throwaway_items' entries must be strings");
                }
                parsed.add(s);
            }
            throwawayItems = parsed;
        } else if (tiRaw != null) {
            throw new IllegalArgumentException("'throwaway_items' must be an array of strings");
        }

        boolean ensureInHotbar = false;
        Object eRaw = m.get("ensure_throwaway_in_hotbar");
        if (eRaw instanceof Boolean b) {
            ensureInHotbar = b;
        } else if (eRaw != null) {
            throw new IllegalArgumentException("'ensure_throwaway_in_hotbar' must be a boolean");
        }

        return new Request(goalType, x, y, z, timeoutSec, tolerance, allowPlace, throwawayItems, ensureInHotbar);
    }

    private static int requireInt(Map<?, ?> m, String key) {
        Object raw = m.get(key);
        if (!(raw instanceof Number n)) throw new IllegalArgumentException("'" + key + "' must be an integer");
        if (n.doubleValue() != n.longValue()) throw new IllegalArgumentException("'" + key + "' must be an integer (not a float)");
        long v = n.longValue();
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) throw new IllegalArgumentException("'" + key + "' out of int range");
        return (int) v;
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
