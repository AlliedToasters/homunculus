package dev.toast.homunculus;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured-field extraction for allowlisted serverbound packets (ml.MD §4a).
 *
 * <p>The codec's job is to map between the raw packet and a structured neural
 * action with pointer-into-observation parameters. Recording needs the raw
 * fields out of the packet so the Python codec can be developed and tested
 * against real (packet, obs) pairs offline before any neural code touches it.
 *
 * <p>First cut: full extraction for the {@link ServerboundMovePlayerPacket}
 * family (~70% of allowlisted volume per Phase 0 stats). The other 7 types
 * stub with {@code "_unimplemented": true} so the JSONL records them with a
 * type tag and timing but no fields — useful for sanity-checking the recording
 * pipeline before we fill in each extractor.
 *
 * <p>Why one type first: the move family is the simplest schema (deltas vs the
 * pre-move obs snapshot), highest volume, and most directly tests the
 * "pointers into observation" hypothesis. Use_item_on / player_action / interact
 * — the pointer-rich ones — get filled in after the pipeline is proven.
 */
public final class PacketFieldExtractor {

    private PacketFieldExtractor() {}

    /**
     * Extract structured fields for the packet. Returns an ordered map suitable
     * for JSONL emission. Never returns null; for unimplemented types returns
     * {@code {"_unimplemented": true}}.
     */
    public static Map<String, Object> extract(Packet<?> packet) {
        if (packet instanceof ServerboundMovePlayerPacket p) {
            return extractMove(p);
        }
        return unimplemented();
    }

    /**
     * {@link ServerboundMovePlayerPacket} covers four wire types in 1.21.4:
     * {@code move_player_pos}, {@code move_player_pos_rot}, {@code move_player_rot},
     * {@code move_player_status_only}. The abstract base holds all the data;
     * {@link ServerboundMovePlayerPacket#hasPosition()} /
     * {@link ServerboundMovePlayerPacket#hasRotation()} disambiguate which
     * fields are populated on the wire. Unpopulated fields come back as the
     * default we pass to the getter (NaN here, so the codec can detect them).
     */
    private static Map<String, Object> extractMove(ServerboundMovePlayerPacket p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("has_pos", p.hasPosition());
        m.put("has_rot", p.hasRotation());
        if (p.hasPosition()) {
            m.put("x", p.getX(Double.NaN));
            m.put("y", p.getY(Double.NaN));
            m.put("z", p.getZ(Double.NaN));
        }
        if (p.hasRotation()) {
            m.put("yaw", (double) p.getYRot(Float.NaN));
            m.put("pitch", (double) p.getXRot(Float.NaN));
        }
        m.put("on_ground", p.isOnGround());
        m.put("horizontal_collision", p.horizontalCollision());
        return m;
    }

    private static Map<String, Object> unimplemented() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("_unimplemented", true);
        return m;
    }
}
