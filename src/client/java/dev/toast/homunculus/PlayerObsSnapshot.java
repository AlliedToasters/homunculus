package dev.toast.homunculus;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
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
            String dim
    ) {
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
                    dim == null ? null : dim.toString()
            ));
        });
    }

    /** Lock-free read for the network thread. May return null pre-join or post-disconnect. */
    public static Snapshot latest() {
        return LATEST.get();
    }
}
