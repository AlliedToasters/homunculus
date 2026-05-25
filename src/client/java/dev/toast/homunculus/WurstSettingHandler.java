package dev.toast.homunculus;

import com.google.gson.JsonElement;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * GET  /wurst/setting?hack=AutoDrop&setting=Items
 * POST /wurst/setting  {"hack": "...", "setting": "...", "value": <any JSON>, "op": "<list-only>"}
 *
 * Reads and mutates Wurst hack settings at runtime via reflection. Two write paths:
 *
 *   1. ItemListSetting (e.g. AutoDrop's "Items"): op-aware (replace|add|remove|reset).
 *      `value` is a JSON array of item ids.
 *   2. Everything else (CheckboxSetting, SliderSetting, EnumSetting, BlockListSetting,
 *      TextFieldSetting, …): delegate to Setting.fromJson(JsonElement). `value` is any
 *      JSON shape the setting subclass accepts (boolean / number / string / array).
 *      `op` is rejected on this path — it only applies to ItemListSetting.
 *
 * All mutations run on the client thread; Wurst auto-flushes to settings.json on its
 * normal cycle.
 */
public final class WurstSettingHandler implements HttpHandler {

    private static final long TIMEOUT_MS = 30000;
    private static final int MAX_BODY_BYTES = 262144; // 256KB — full item registry payload ≈ 70KB

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method)) {
                handleGet(exchange);
            } else if ("POST".equalsIgnoreCase(method)) {
                handlePost(exchange);
            } else {
                respond(exchange, 405, failure("bad_request", "method not allowed"));
            }
        } finally {
            exchange.close();
        }
    }

    // ── GET ──────────────────────────────────────────────────────────────────

    private void handleGet(HttpExchange exchange) throws IOException {
        if (!Wurst.isApiLoaded()) {
            respond(exchange, 200, failure("wurst_not_loaded", "Wurst Client not present at runtime"));
            return;
        }

        Map<String, String> q = parseQuery(exchange.getRequestURI());
        String hackName = q.get("hack");
        String settingName = q.get("setting");
        if (hackName == null || hackName.isEmpty()) {
            respond(exchange, 400, failure("bad_request", "'hack' query parameter is required"));
            return;
        }
        if (settingName == null || settingName.isEmpty()) {
            respond(exchange, 400, failure("bad_request", "'setting' query parameter is required"));
            return;
        }

        Map<String, Object> body;
        try {
            final String fh = hackName, fs = settingName;
            body = ClientThread.supply(() -> doRead(fh, fs))
                    .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            respond(exchange, 504, failure("internal_error", "client thread timeout"));
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            respond(exchange, 500, failure("internal_error", "interrupted"));
            return;
        } catch (ExecutionException e) {
            HomunculusClient.LOGGER.error("WurstSettingHandler GET failed", e.getCause());
            respond(exchange, 500, failure("internal_error", "unexpected error"));
            return;
        }
        respond(exchange, 200, body);
    }

    private static Map<String, Object> doRead(String hackName, String settingName) {
        Object hack = Wurst.findHack(hackName);
        if (hack == null) return failure("hack_not_found",
                "no Wurst hack named '" + hackName + "'. Try GET /wurst/status to list available hacks.");

        if (!Wurst.isSettingApiReady()) return failure("unsupported_setting_type",
                "Wurst setting reflection not available in this version");

        Object setting = Wurst.findSetting(hack, settingName);
        if (setting == null) return failure("setting_not_found",
                "hack '" + Wurst.getName(hack) + "' has no setting named '" + settingName + "'");

        if (!Wurst.isItemListSetting(setting)) return failure("unsupported_setting_type",
                "setting '" + settingName + "' is not an ItemListSetting (GET only supports ItemListSetting)");

        List<String> items = new ArrayList<>(Wurst.getItemNames(setting));
        boolean isDefault = Wurst.isItemListAtDefaults(setting);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("hack", Wurst.getName(hack));
        m.put("setting", settingName);
        m.put("after", items);
        m.put("is_default_after", isDefault);
        return m;
    }

    // ── POST ─────────────────────────────────────────────────────────────────

    private void handlePost(HttpExchange exchange) throws IOException {
        if (!Wurst.isApiLoaded()) {
            respond(exchange, 200, failure("wurst_not_loaded", "Wurst Client not present at runtime"));
            return;
        }

        PostRequest req;
        try {
            req = parseBody(exchange);
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, failure("bad_request", e.getMessage()));
            return;
        }

        Map<String, Object> body;
        final PostRequest fReq = req;
        try {
            body = ClientThread.supply(() -> doMutate(fReq))
                    .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            respond(exchange, 504, failure("internal_error", "client thread timeout"));
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            respond(exchange, 500, failure("internal_error", "interrupted"));
            return;
        } catch (ExecutionException e) {
            HomunculusClient.LOGGER.error("WurstSettingHandler POST failed", e.getCause());
            respond(exchange, 500, failure("internal_error", "unexpected error"));
            return;
        }
        respond(exchange, 200, body);
    }

    private static Map<String, Object> doMutate(PostRequest req) {
        Object hack = Wurst.findHack(req.hackName);
        if (hack == null) return failure("hack_not_found",
                "no Wurst hack named '" + req.hackName + "'. Try GET /wurst/status to list available hacks.");

        if (!Wurst.isSettingApiReady()) return failure("unsupported_setting_type",
                "Wurst setting reflection not available in this version");

        Object setting = Wurst.findSetting(hack, req.settingName);
        if (setting == null) return failure("setting_not_found",
                "hack '" + Wurst.getName(hack) + "' has no setting named '" + req.settingName + "'");

        if (Wurst.isItemListSetting(setting)) {
            return mutateItemList(hack, setting, req);
        }

        // Generic fall-through: delegate to Setting.fromJson(JsonElement).
        if (req.op != null) {
            return failure("bad_request",
                    "'op' is only valid for ItemListSetting; '" + req.settingName + "' is a "
                            + Wurst.settingTypeName(setting));
        }

        JsonElement before = Wurst.settingToJson(setting);
        JsonElement valueEl;
        try {
            valueEl = Json.toJsonElement(req.value);
        } catch (IllegalArgumentException e) {
            return failure("bad_request", e.getMessage());
        }
        Throwable err = Wurst.settingFromJson(setting, valueEl);
        if (err != null) {
            return failure("wrong_value_type",
                    "Setting '" + req.settingName + "' on hack '" + Wurst.getName(hack)
                            + "' (" + Wurst.settingTypeName(setting) + ") rejected value: "
                            + err.getClass().getSimpleName() + ": " + String.valueOf(err.getMessage()));
        }
        JsonElement after = Wurst.settingToJson(setting);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("hack", Wurst.getName(hack));
        m.put("setting", req.settingName);
        m.put("type", Wurst.settingTypeName(setting));
        m.put("changed", !Objects.equals(before, after));
        return m;
    }

    private static Map<String, Object> mutateItemList(Object hack, Object setting, PostRequest req) {
        Op op = req.op != null ? req.op : Op.REPLACE;

        // Coerce req.value into a List<String> of item ids for the op-aware path.
        // RESET ignores value entirely.
        List<String> ids = new ArrayList<>();
        if (op != Op.RESET) {
            if (!(req.value instanceof List<?> rawList)) {
                return failure("wrong_value_type",
                        "ItemListSetting '" + req.settingName + "' expects an array of item ids; got "
                                + Json.typeLabel(req.value));
            }
            for (Object o : rawList) {
                if (!(o instanceof String s)) {
                    return failure("wrong_value_type",
                            "ItemListSetting '" + req.settingName + "' entries must be strings");
                }
                ids.add(s);
            }
        }

        List<Item> resolvedItems = new ArrayList<>();
        if (op != Op.RESET) {
            List<String> unknown = new ArrayList<>();
            for (String s : ids) {
                String full = s.contains(":") ? s : "minecraft:" + s;
                ResourceLocation rl = ResourceLocation.tryParse(full);
                if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) {
                    unknown.add(s);
                    continue;
                }
                resolvedItems.add(BuiltInRegistries.ITEM.getValue(rl));
            }
            if (!unknown.isEmpty()) {
                Map<String, Object> err = failure("unknown_item_id",
                        "unknown item id(s): " + String.join(", ", unknown));
                err.put("unknown", unknown);
                return err;
            }
        }

        List<String> before = new ArrayList<>(Wurst.getItemNames(setting));

        switch (op) {
            case REPLACE -> {
                if (resolvedItems.isEmpty()) {
                    // replace with empty value[] restores defaults (mirrors Wurst's "default" sentinel)
                    Wurst.itemListReset(setting);
                } else {
                    // Remove back-to-front so indices stay valid, then add new items.
                    for (int i = before.size() - 1; i >= 0; i--) {
                        Wurst.itemListRemoveAt(setting, i);
                    }
                    for (Item item : resolvedItems) {
                        Wurst.itemListAdd(setting, item);
                    }
                }
            }
            case ADD -> {
                for (Item item : resolvedItems) {
                    Wurst.itemListAdd(setting, item);
                }
            }
            case REMOVE -> {
                // Collect indices of items to remove, then delete back-to-front.
                List<Integer> indices = new ArrayList<>();
                for (Item item : resolvedItems) {
                    String id = BuiltInRegistries.ITEM.getKey(item).toString();
                    int idx = before.indexOf(id);
                    if (idx >= 0 && !indices.contains(idx)) indices.add(idx);
                }
                indices.sort(Comparator.reverseOrder());
                for (int idx : indices) {
                    Wurst.itemListRemoveAt(setting, idx);
                }
            }
            case RESET -> Wurst.itemListReset(setting);
        }

        List<String> after = new ArrayList<>(Wurst.getItemNames(setting));
        boolean isDefault = Wurst.isItemListAtDefaults(setting);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("hack", Wurst.getName(hack));
        m.put("setting", req.settingName);
        m.put("type", "ItemListSetting");
        m.put("op", op.label);
        m.put("before", before);
        m.put("after", after);
        m.put("changed", !before.equals(after));
        m.put("is_default_after", isDefault);
        return m;
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private enum Op {
        REPLACE("replace"), ADD("add"), REMOVE("remove"), RESET("reset");

        final String label;
        Op(String label) { this.label = label; }

        static Op parse(String s) {
            for (Op op : values()) if (op.label.equalsIgnoreCase(s)) return op;
            throw new IllegalArgumentException("'op' must be one of: replace, add, remove, reset");
        }
    }

    /** value is the raw parsed JSON tree (Map/List/Boolean/Number/String/null). op may be null. */
    private record PostRequest(String hackName, String settingName, Op op, Object value) {}

    private static PostRequest parseBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) throw new IllegalArgumentException("body too large");
        String raw = new String(bytes, StandardCharsets.UTF_8).trim();
        if (raw.isEmpty()) throw new IllegalArgumentException("empty body");

        Object parsed;
        try {
            parsed = Json.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid JSON: " + e.getMessage());
        }
        if (!(parsed instanceof Map<?, ?> m)) throw new IllegalArgumentException("expected JSON object");

        Object hackRaw = m.get("hack");
        if (!(hackRaw instanceof String hackName) || hackName.isEmpty())
            throw new IllegalArgumentException("'hack' must be a non-empty string");

        Object settingRaw = m.get("setting");
        if (!(settingRaw instanceof String settingName) || settingName.isEmpty())
            throw new IllegalArgumentException("'setting' must be a non-empty string");

        Op op = null;
        Object opRaw = m.get("op");
        if (opRaw instanceof String opStr) {
            op = Op.parse(opStr);
        } else if (opRaw != null) {
            throw new IllegalArgumentException("'op' must be a string");
        }

        Object value = m.containsKey("value") ? m.get("value") : null;
        if (op != Op.RESET && !m.containsKey("value")) {
            throw new IllegalArgumentException("'value' is required");
        }

        return new PostRequest(hackName, settingName, op, value);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq < 0) out.put(decode(pair), "");
            else out.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
        }
        return out;
    }

    private static String decode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
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
