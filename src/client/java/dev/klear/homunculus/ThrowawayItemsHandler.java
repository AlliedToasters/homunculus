package dev.klear.homunculus;

import baritone.api.BaritoneAPI;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * GET/POST /baritone/throwaway_items.
 *
 * <p>Surfaces Baritone's {@code acceptableThrowawayItems} setting — the list of
 * items the pathfinder is allowed to place mid-path (pillaring up, bridging a
 * gap). Baritone's stock default is just {dirt, cobblestone, netherrack, stone};
 * a fill that runs out of those and switches to (say) diorite leaves the
 * pathfinder unable to ascend and stalls in long A* searches looking for
 * ground-level detours that don't exist.
 *
 * <p>GET returns {@code {success, items: [minecraft:id, ...]}}.
 * POST replaces the list. Body: {@code {"items": ["minecraft:diorite", ...]}}.
 * Unknown ids return 400 — the caller should normalize before sending.
 *
 * <p>The replacement is persistent in the Baritone singleton until the JVM
 * exits or another caller (e.g. Goto's per-call override + restore) writes it.
 * Goto's restore writes back the pre-call value, so calling /goto in between
 * doesn't clobber what was set here.
 */
public final class ThrowawayItemsHandler implements HttpHandler {

    private static final long GAME_THREAD_TIMEOUT_MS = 5_000;
    private static final int MAX_BODY_BYTES = 8192;

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
        List<Item> items;
        try {
            items = ClientThread.supply(
                    () -> new ArrayList<>(BaritoneAPI.getSettings().acceptableThrowawayItems.value))
                    .get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            respond(exchange, 200, failureBody("internal_error", "read failed: " + rootMessage(e)));
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("items", toIdList(items));
        respond(exchange, 200, body);
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
        Object rawItems = map.get("items");
        if (!(rawItems instanceof List<?> rawList)) {
            respond(exchange, 400, Map.of("error", "missing 'items' list"));
            return;
        }

        List<Item> resolved = new ArrayList<>(rawList.size());
        List<String> unknown = new ArrayList<>();
        for (Object o : rawList) {
            if (!(o instanceof String s)) {
                respond(exchange, 400, Map.of("error", "items must be strings"));
                return;
            }
            String full = s.contains(":") ? s : "minecraft:" + s;
            ResourceLocation rl = ResourceLocation.tryParse(full);
            if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) {
                unknown.add(s);
                continue;
            }
            Item it = BuiltInRegistries.ITEM.getValue(rl);
            if (!resolved.contains(it)) resolved.add(it);
        }
        if (!unknown.isEmpty()) {
            respond(exchange, 400, Map.of(
                    "error", "unknown item id(s)",
                    "unknown", unknown));
            return;
        }

        try {
            ClientThread.supply(() -> {
                BaritoneAPI.getSettings().acceptableThrowawayItems.value = resolved;
                return null;
            }).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            respond(exchange, 200, failureBody("internal_error", "write failed: " + rootMessage(e)));
            return;
        }

        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("success", true);
        ok.put("items", toIdList(resolved));
        respond(exchange, 200, ok);
    }

    private static List<String> toIdList(List<Item> items) {
        List<String> out = new ArrayList<>(items.size());
        for (Item it : items) {
            out.add(BuiltInRegistries.ITEM.getKey(it).toString());
        }
        return out;
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
