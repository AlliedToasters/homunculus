package dev.toast.homunculus;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

import java.util.Map;

/**
 * Reverse of {@link PacketFieldExtractor}: build a {@link Packet} from a
 * fields dict. Used by {@link CodecPassthrough} when {@code substitute=true} —
 * the codec server's decoded fields are reconstructed into a packet that
 * goes on the wire instead of the original (ml.MD §4a step 2, interpretation A).
 *
 * <p><b>Smoke-test scope:</b> only the {@link ServerboundMovePlayerPacket}
 * family is wired today (move_player_pos / pos_rot / rot / status_only).
 * These are >80% of allowlisted traffic; if substitution works for them
 * cleanly, the per-packet-type lift for the other 7 is mechanical.
 *
 * <p>Returns {@code null} for any packet type without a reconstructor, or
 * for malformed fields — the caller falls back to the original packet so a
 * gap in coverage never breaks the wire.
 */
public final class PacketReconstructor {

    private PacketReconstructor() {}

    /**
     * @param packetId  the packet's resource location string
     *                  (e.g. {@code "minecraft:move_player_pos_rot"})
     * @param fields    fields dict from the codec server's {@code decoded}
     *                  response, matching what
     *                  {@link PacketFieldExtractor#extract(Packet)} would
     *                  have produced
     * @return reconstructed packet, or {@code null} if the type isn't
     *         supported or fields are malformed
     */
    public static Packet<?> build(String packetId, Map<String, Object> fields) {
        if (packetId == null || fields == null) return null;
        try {
            return switch (packetId) {
                case "minecraft:move_player_pos_rot" -> buildMovePosRot(fields);
                case "minecraft:move_player_pos" -> buildMovePos(fields);
                case "minecraft:move_player_rot" -> buildMoveRot(fields);
                case "minecraft:move_player_status_only" -> buildMoveStatusOnly(fields);
                default -> null;
            };
        } catch (Throwable t) {
            HomunculusClient.LOGGER.debug("[reconstructor] failed for {}: {}", packetId, t.toString());
            return null;
        }
    }

    public static boolean canReconstruct(String packetId) {
        return switch (packetId) {
            case "minecraft:move_player_pos_rot",
                 "minecraft:move_player_pos",
                 "minecraft:move_player_rot",
                 "minecraft:move_player_status_only" -> true;
            default -> false;
        };
    }

    private static Packet<?> buildMovePosRot(Map<String, Object> f) {
        double x = asDouble(f.get("x"));
        double y = asDouble(f.get("y"));
        double z = asDouble(f.get("z"));
        float yaw = (float) asDouble(f.get("yaw"));
        float pitch = (float) asDouble(f.get("pitch"));
        boolean onGround = asBool(f.get("on_ground"));
        boolean hColl = asBool(f.get("horizontal_collision"));
        return new ServerboundMovePlayerPacket.PosRot(x, y, z, yaw, pitch, onGround, hColl);
    }

    private static Packet<?> buildMovePos(Map<String, Object> f) {
        double x = asDouble(f.get("x"));
        double y = asDouble(f.get("y"));
        double z = asDouble(f.get("z"));
        boolean onGround = asBool(f.get("on_ground"));
        boolean hColl = asBool(f.get("horizontal_collision"));
        return new ServerboundMovePlayerPacket.Pos(x, y, z, onGround, hColl);
    }

    private static Packet<?> buildMoveRot(Map<String, Object> f) {
        float yaw = (float) asDouble(f.get("yaw"));
        float pitch = (float) asDouble(f.get("pitch"));
        boolean onGround = asBool(f.get("on_ground"));
        boolean hColl = asBool(f.get("horizontal_collision"));
        return new ServerboundMovePlayerPacket.Rot(yaw, pitch, onGround, hColl);
    }

    private static Packet<?> buildMoveStatusOnly(Map<String, Object> f) {
        boolean onGround = asBool(f.get("on_ground"));
        boolean hColl = asBool(f.get("horizontal_collision"));
        return new ServerboundMovePlayerPacket.StatusOnly(onGround, hColl);
    }

    private static double asDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        throw new IllegalArgumentException("expected number, got " + (o == null ? "null" : o.getClass()));
    }

    private static boolean asBool(Object o) {
        if (o instanceof Boolean b) return b;
        throw new IllegalArgumentException("expected boolean, got " + (o == null ? "null" : o.getClass()));
    }
}
