package dev.toast.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FillHandler implements HttpHandler {
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

            ResourceLocation id = ResourceLocation.tryParse(req.blockId);
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                int[] box = new int[] {
                        Math.min(req.x1, req.x2), Math.min(req.y1, req.y2), Math.min(req.z1, req.z2),
                        Math.max(req.x1, req.x2), Math.max(req.y1, req.y2), Math.max(req.z1, req.z2)
                };
                int volume = (box[3] - box[0] + 1) * (box[4] - box[1] + 1) * (box[5] - box[2] + 1);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("success", false);
                body.put("reason", "invalid_request");
                body.put("block", req.blockId);
                body.put("box", List.of(box[0], box[1], box[2], box[3], box[4], box[5]));
                body.put("volume", volume);
                body.put("remaining", 0);
                body.put("message", "unknown block id '" + req.blockId + "'");
                respond(exchange, 200, body);
                return;
            }
            Block block = BuiltInRegistries.BLOCK.getValue(id);

            Fill.Outcome outcome = Fill.run(
                    req.blockId, block,
                    req.x1, req.y1, req.z1, req.x2, req.y2, req.z2,
                    req.timeoutSeconds);
            respond(exchange, 200, toBody(outcome));
        } finally {
            exchange.close();
        }
    }

    private static Map<String, Object> toBody(Fill.Outcome outcome) {
        Map<String, Object> body = new LinkedHashMap<>();
        int[] b = outcome.box();
        if (outcome instanceof Fill.Filled f) {
            body.put("success", true);
            body.put("reason", f.reason());
            body.put("block", f.block());
            body.put("box", List.of(b[0], b[1], b[2], b[3], b[4], b[5]));
            body.put("volume", f.volume());
            body.put("remaining", f.remaining());
            body.put("message", f.message());
        } else if (outcome instanceof Fill.Failed f) {
            body.put("success", false);
            body.put("reason", f.reason());
            body.put("block", f.block());
            body.put("box", List.of(b[0], b[1], b[2], b[3], b[4], b[5]));
            body.put("volume", f.volume());
            body.put("remaining", f.remaining());
            body.put("message", f.message());
        }
        return body;
    }

    private record Request(String blockId, int x1, int y1, int z1, int x2, int y2, int z2, long timeoutSeconds) {}

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

        Object blockRaw = m.get("block");
        if (!(blockRaw instanceof String bs) || bs.isBlank()) {
            throw new IllegalArgumentException("'block' must be a non-empty string");
        }
        String blockId = bs.contains(":") ? bs : ("minecraft:" + bs);

        int x1 = requireInt(m, "x1");
        int y1 = requireInt(m, "y1");
        int z1 = requireInt(m, "z1");
        int x2 = requireInt(m, "x2");
        int y2 = requireInt(m, "y2");
        int z2 = requireInt(m, "z2");

        long timeoutSec = Fill.DEFAULT_TIMEOUT_SECONDS;
        Object toRaw = m.get("timeout_seconds");
        if (toRaw instanceof Number tn) {
            long t = tn.longValue();
            if (t < 1) throw new IllegalArgumentException("'timeout_seconds' must be >= 1");
            timeoutSec = t;
        } else if (toRaw != null) {
            throw new IllegalArgumentException("'timeout_seconds' must be a number");
        }

        return new Request(blockId, x1, y1, z1, x2, y2, z2, timeoutSec);
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
