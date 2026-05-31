package dev.toast.homunculus;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pre-move player observation snapshot for the codec recorder (ml.MD §4a).
 *
 * <p>Updated end-of-tick on the client/render thread; read lock-free from the
 * network thread by {@link dev.toast.homunculus.mixin.OutboundPacketMixin}.
 * The temporal ordering is the load-bearing detail: during tick {@code T} the
 * client moves the player and emits {@code ServerboundMovePlayerPacket} with
 * the post-move pose; {@link ClientTickEvents#END_CLIENT_TICK} fires after
 * that send. So while the mixin runs in tick {@code T}, this reference still
 * holds the snapshot written at the END of tick {@code T-1} — i.e. the
 * pre-move state the codec needs to express the packet as a delta.
 *
 * <p>First-packet edge case: the reference is null until at least one tick has
 * elapsed. The recorder treats null as "snapshot unavailable" and skips the
 * record (counts a {@code dropped_no_obs}). In practice that's only the first
 * packet after world join.
 *
 * <p>Two serializations (neural_interface.md §8e light-tier split):
 * <ul>
 *   <li>{@link Snapshot#toJson()} — minimal pose only. This is the
 *       codec-facing obs (the round-trip reference frame); the live codec
 *       passthrough / inference path sends it synchronously per packet, so it
 *       stays lean.</li>
 *   <li>{@link Snapshot#toRecordJson()} — the recording superset: minimal pose
 *       plus R2 stats/inventory (§8b) and the control-stack meta-observables
 *       (§8f). This is what {@link PacketRecorder} writes per line.</li>
 * </ul>
 * The heavy R4 channels (block grid, vision) are NOT here — they go to a
 * separate tick-indexed sidecar (§8e). The ONE exception (neural_interface.md
 * §17.2.2) is a BOUNDED {@code entity_set}: the nearest living entities,
 * nearest-first (the §13.1 candidate order), each carrying its int network
 * {@code id} (= the {@code entity_id} field of {@code ServerboundInteract},
 * resolved by {@code level.getEntity(int)}). This is the substrate the
 * discrete-target codec needs to reparameterize {@code interact.entity_id} into
 * an {@code entity_set} index (a pointer into obs, not a raw handle) and is the
 * gate on §17.2.2. It is captured on the client thread here (where the entity
 * list is safe to read) and only SERIALIZED on the send thread, so the
 * latency-sensitive substitute path does no world query — same discipline as
 * the stats/inventory extras. Bounded (radius {@link #ENTITY_SET_RADIUS},
 * limit {@link #ENTITY_SET_LIMIT}) so the per-packet obs stays lean.
 */
public final class PlayerObsSnapshot {

    /** entity_set search half-extent (blocks). Generous vs the ~5-6 attack reach
     *  so a decoy a few blocks past the target is still observed, but bounded so
     *  the per-packet obs stays small. */
    private static final double ENTITY_SET_RADIUS = 16.0;
    /** Max entities in the obs entity_set (nearest-first cap). */
    private static final int ENTITY_SET_LIMIT = 16;

    public record Snapshot(
            long tickCounter,
            long capturedAtMs,
            double x,
            double y,
            double z,
            double yaw,
            double pitch,
            boolean onGround,
            String dim,
            List<Map<String, Object>> entitySet,
            Map<String, Object> policy,
            Map<String, Object> extras
    ) {
        /** Minimal codec-facing obs (round-trip reference frame) + the bounded
         *  R3 entity_set (§17.2.2). The entity_set is pre-built on the tick
         *  thread; this just serializes it. */
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tick", tickCounter);
            m.put("captured_at_ms", capturedAtMs);
            m.put("x", x);
            m.put("y", y);
            m.put("z", z);
            m.put("yaw", yaw);
            m.put("pitch", pitch);
            m.put("on_ground", onGround);
            m.put("dim", dim);
            m.put("entity_set", entitySet == null ? List.of() : entitySet);
            // g_t: the active executor policy (Wurst KillAura filter stack +
            // Priority). The §18.1 conditioning variable the codec reads off the
            // wire to predict the interact target across filter modes.
            m.put("policy", policy == null ? Map.of() : policy);
            return m;
        }

        /** Recording superset: minimal pose + R2 stats/inventory + meta (§8e/§8f). */
        public Map<String, Object> toRecordJson() {
            Map<String, Object> m = toJson();
            if (extras != null) {
                m.putAll(extras);
            }
            return m;
        }
    }

    private static final AtomicReference<Snapshot> LATEST = new AtomicReference<>(null);
    private static final AtomicLong TICK_COUNTER = new AtomicLong();

    private PlayerObsSnapshot() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            LocalPlayer p = client.player;
            if (p == null) {
                LATEST.set(null);
                return;
            }
            long tick = TICK_COUNTER.incrementAndGet();
            ResourceLocation dim = p.level().dimension().location();
            LATEST.set(new Snapshot(
                    tick,
                    System.currentTimeMillis(),
                    p.getX(),
                    p.getY(),
                    p.getZ(),
                    p.getYRot(),
                    p.getXRot(),
                    p.onGround(),
                    dim == null ? null : dim.toString(),
                    buildEntitySet(p),
                    buildPolicy(),
                    buildExtras(p, tick)
            ));
        });
    }

    /**
     * R2 stats/inventory (§8b) + control-stack meta (§8f), captured on the
     * client thread. Inventory shape mirrors {@link InventoryHandler} so the
     * recording and the live endpoint agree. Built every tick — cheap relative
     * to the heavy sidecar — so each recorded packet line carries it.
     */
    private static Map<String, Object> buildExtras(LocalPlayer p, long tick) {
        Map<String, Object> m = new LinkedHashMap<>();

        // --- R2 stats ---
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("health", p.getHealth());
        stats.put("max_health", p.getMaxHealth());
        FoodData food = p.getFoodData();
        stats.put("food", food.getFoodLevel());
        stats.put("saturation", food.getSaturationLevel());
        stats.put("air", p.getAirSupply());
        stats.put("max_air", p.getMaxAirSupply());
        stats.put("armor", p.getArmorValue());
        stats.put("xp_level", p.experienceLevel);
        stats.put("in_water", p.isInWater());
        stats.put("in_lava", p.isInLava());
        stats.put("on_fire", p.isOnFire());
        m.put("stats", stats);

        // --- R2 inventory (shape matches InventoryHandler) ---
        m.put("inventory", buildInventory(p.getInventory()));

        // --- §8f meta-observables (carry-forwarded from /obs/meta) ---
        m.putAll(AgentMeta.INSTANCE.read(tick));

        return m;
    }

    /**
     * Bounded R3 entity_set for the codec-facing obs (§17.2.2). The nearest
     * living entities (excl. the local player), nearest-first via the shared
     * {@link Entities#query} highway — the SAME ordering §13.1 used to predict
     * the attack target. Each record carries the int network {@code id}
     * ({@code Entity.getId()}), which is exactly the {@code entity_id} the
     * interact codec must point at; plus type + position + distance so the codec
     * (and §18) has the geometry to reconstruct the target from obs.
     *
     * <p>Client-thread only (reads the live entity list). Cheap, bounded; on the
     * same tick-thread budget as the stats/inventory extras.
     */
    private static List<Map<String, Object>> buildEntitySet(LocalPlayer p) {
        List<Entity> matched = Entities.query(
                p, p.level(), ENTITY_SET_RADIUS, e -> e instanceof LivingEntity);
        List<Map<String, Object>> out = new ArrayList<>(Math.min(matched.size(), ENTITY_SET_LIMIT));
        for (Entity e : matched) {
            if (out.size() >= ENTITY_SET_LIMIT) break;
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("id", e.getId());                 // int network id == interact.entity_id
            rec.put("type", Entities.typeId(e));
            List<Object> pos = new ArrayList<>(3);
            pos.add(e.getX());
            pos.add(e.getY());
            pos.add(e.getZ());
            rec.put("position", pos);
            rec.put("distance", p.distanceTo(e));
            out.add(rec);
        }
        return out;
    }

    /**
     * The active executor policy (g_t) for the codec obs (§18.1): KillAura's
     * target-selection settings — {@code Priority}, {@code Range}, and every
     * {@code "Filter ..."} toggle — read via the Wurst reflection API on the
     * client thread (cheap field reads, on the same tick budget as entity_set).
     * Keys are the Wurst setting display names; values are boolean / number /
     * string. Empty if Wurst or KillAura is absent. This is the conditioning
     * variable the interact codec reads off the wire to predict the attack
     * target across filter modes (same scene, different target by policy).
     */
    private static Map<String, Object> buildPolicy() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!Wurst.isApiLoaded() || !Wurst.isSettingApiReady()) return out;
        Object hack = Wurst.findHack("KillAura");
        if (hack == null) return out;
        for (Object setting : Wurst.getSettingsMap(hack).values()) {
            String name = Wurst.settingName(setting);
            if (name == null) continue;
            if (!(name.startsWith("Filter") || name.equals("Priority") || name.equals("Range"))) {
                continue;
            }
            Object val = jsonToValue(Wurst.settingToJson(setting));
            if (val != null) out.put(name, val);
        }
        return out;
    }

    /** Setting.toJson() JsonElement → a plain JSON value for the obs map. */
    private static Object jsonToValue(JsonElement el) {
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) return null;
        JsonPrimitive p = el.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isNumber()) return p.getAsDouble();
        return p.getAsString();
    }

    private static Map<String, Object> buildInventory(Inventory inv) {
        List<Object> main = new ArrayList<>();
        NonNullList<ItemStack> items = inv.items;
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("slot", i);
                e.put("id", itemId(stack));
                e.put("count", stack.getCount());
                main.add(e);
            }
        }
        Map<String, Object> armor = new LinkedHashMap<>();
        armor.put("feet", stackOrNull(inv.armor.get(0)));
        armor.put("legs", stackOrNull(inv.armor.get(1)));
        armor.put("chest", stackOrNull(inv.armor.get(2)));
        armor.put("head", stackOrNull(inv.armor.get(3)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("main", main);
        result.put("armor", armor);
        result.put("offhand", stackOrNull(inv.offhand.get(0)));
        result.put("selected_slot", inv.selected);
        return result;
    }

    private static Object stackOrNull(ItemStack stack) {
        if (stack.isEmpty()) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", itemId(stack));
        m.put("count", stack.getCount());
        return m;
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** Lock-free read for the network thread. May return null pre-join or post-disconnect. */
    public static Snapshot latest() {
        return LATEST.get();
    }

    /** Current client tick counter (for {@link AgentMeta} ticks-since stamping). */
    public static long currentTick() {
        return TICK_COUNTER.get();
    }
}
