package dev.toast.homunculus;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
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
 * The heavy R3/R4 channels (block grid, entity set, vision) are NOT here —
 * they go to a separate tick-indexed sidecar (§8e), built in a later phase.
 */
public final class PlayerObsSnapshot {

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
            Map<String, Object> extras
    ) {
        /** Minimal codec-facing obs (round-trip reference frame). */
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
