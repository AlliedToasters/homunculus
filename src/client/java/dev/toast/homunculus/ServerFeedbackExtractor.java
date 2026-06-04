package dev.toast.homunculus;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured-field extraction for the clientbound <em>corrective-feedback</em>
 * packets the server sends back when it rejects or overrides a client move
 * (the inbound mirror of {@link PacketFieldExtractor}).
 *
 * <p>This is the load-bearing half of the rubber-band signal (see
 * {@code results/sprintA/FUTURE_rubberband_signal.md}). The outbound recorder
 * already captures the action the client emitted; here we capture the server's
 * reply so an offline join can attribute a correction to the action that
 * provoked it. Two corrective types:
 *
 * <ul>
 *   <li>{@code minecraft:player_position} —
 *       {@link ClientboundPlayerPositionPacket}. The graded rubber-band: the
 *       server yanks the player back to an accepted pose ("moved too quickly /
 *       wrongly"), also used for legit teleports (spawn, {@code /tp}). The
 *       {@code relatives} set + the magnitude of the correction vs the client's
 *       believed pose distinguish anti-cheat snap-backs from intended teleports
 *       offline — we record both and don't try to classify on the wire.</li>
 *   <li>{@code minecraft:set_entity_motion} —
 *       {@link ClientboundSetEntityMotionPacket}. A velocity override; for the
 *       local player it's knockback / server-side motion reset. The mixin
 *       filters to the local player before calling, so combat-time motion
 *       packets for other entities never reach here.</li>
 * </ul>
 *
 * <p>Returns {@code null} for any non-corrective packet so the tap can use it as
 * a relevance filter.
 */
public final class ServerFeedbackExtractor {

    private ServerFeedbackExtractor() {}

    /** Structured fields for a corrective packet, or {@code null} if not one. */
    public static Map<String, Object> extract(Packet<?> packet) {
        if (packet instanceof ClientboundPlayerPositionPacket p) return extractPosition(p);
        if (packet instanceof ClientboundSetEntityMotionPacket p) return extractMotion(p);
        return null;
    }

    /**
     * {@link ClientboundPlayerPositionPacket} (1.21.4 form): a teleport id, a
     * {@link PositionMoveRotation} (absolute-or-relative target pose + delta
     * movement), and the {@link Relative} set marking which fields are deltas.
     * A pure rubber-band correction is typically absolute (empty/positional
     * relatives) — but we emit the raw relatives and let the offline join
     * decide.
     */
    private static Map<String, Object> extractPosition(ClientboundPlayerPositionPacket p) {
        PositionMoveRotation c = p.change();
        Vec3 pos = c.position();
        Vec3 dm = c.deltaMovement();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("teleport_id", p.id());
        m.put("x", pos.x);
        m.put("y", pos.y);
        m.put("z", pos.z);
        m.put("yaw", (double) c.yRot());
        m.put("pitch", (double) c.xRot());
        m.put("dvx", dm.x);
        m.put("dvy", dm.y);
        m.put("dvz", dm.z);
        List<String> relatives = new ArrayList<>();
        for (Relative r : p.relatives()) {
            relatives.add(r.name());
        }
        Collections.sort(relatives);
        m.put("relatives", relatives);
        return m;
    }

    /**
     * {@link ClientboundSetEntityMotionPacket} — entity velocity override. The
     * getters already decode the on-wire fixed-point shorts back to blocks/tick
     * doubles. {@code entity_id} is preserved so the offline join can confirm
     * it targets the player (the mixin filters, but the field documents it).
     */
    private static Map<String, Object> extractMotion(ClientboundSetEntityMotionPacket p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entity_id", p.getId());
        m.put("dvx", p.getXa());
        m.put("dvy", p.getYa());
        m.put("dvz", p.getZa());
        return m;
    }
}
