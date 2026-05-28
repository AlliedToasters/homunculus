package dev.toast.homunculus;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;

/**
 * Reverse of {@link PacketFieldExtractor}: build a {@link Packet} from a
 * fields dict. Used by {@link CodecPassthrough} when {@code substitute=true} —
 * the codec server's decoded fields are reconstructed into a packet that
 * goes on the wire instead of the original (ml.MD §4a step 2, interpretation A).
 *
 * <p>Coverage: all 11 codec wire types in the {@code SPATIAL_PLAY} allowlist.
 *
 * <p>The {@code interact} and {@code player_command} packets require a
 * client-side {@link Entity} reference (the public constructors / static
 * factories take {@code Entity}, not {@code entity_id}). Resolved via
 * {@link Minecraft#level} — if the entity is unloaded (out of range, gone)
 * the reconstruction returns {@code null} and the caller falls back to the
 * original packet.
 *
 * <p>Returns {@code null} for any unsupported packet type or malformed
 * fields — the caller falls back to the original packet so a gap in
 * coverage never breaks the wire.
 */
public final class PacketReconstructor {

    private PacketReconstructor() {}

    public static Packet<?> build(String packetId, Map<String, Object> fields) {
        if (packetId == null || fields == null) return null;
        try {
            return switch (packetId) {
                case "minecraft:move_player_pos_rot" -> buildMovePosRot(fields);
                case "minecraft:move_player_pos" -> buildMovePos(fields);
                case "minecraft:move_player_rot" -> buildMoveRot(fields);
                case "minecraft:move_player_status_only" -> buildMoveStatusOnly(fields);
                case "minecraft:swing" -> buildSwing(fields);
                case "minecraft:player_input" -> buildPlayerInput(fields);
                case "minecraft:player_command" -> buildPlayerCommand(fields);
                case "minecraft:use_item" -> buildUseItem(fields);
                case "minecraft:use_item_on" -> buildUseItemOn(fields);
                case "minecraft:player_action" -> buildPlayerAction(fields);
                case "minecraft:interact" -> buildInteract(fields);
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
                 "minecraft:move_player_status_only",
                 "minecraft:swing",
                 "minecraft:player_input",
                 "minecraft:player_command",
                 "minecraft:use_item",
                 "minecraft:use_item_on",
                 "minecraft:player_action",
                 "minecraft:interact" -> true;
            default -> false;
        };
    }

    // -----------------------------------------------------------------------
    // Move family — direct constructors
    // -----------------------------------------------------------------------

    private static Packet<?> buildMovePosRot(Map<String, Object> f) {
        return new ServerboundMovePlayerPacket.PosRot(
                asDouble(f.get("x")), asDouble(f.get("y")), asDouble(f.get("z")),
                (float) asDouble(f.get("yaw")), (float) asDouble(f.get("pitch")),
                asBool(f.get("on_ground")), asBool(f.get("horizontal_collision")));
    }

    private static Packet<?> buildMovePos(Map<String, Object> f) {
        return new ServerboundMovePlayerPacket.Pos(
                asDouble(f.get("x")), asDouble(f.get("y")), asDouble(f.get("z")),
                asBool(f.get("on_ground")), asBool(f.get("horizontal_collision")));
    }

    private static Packet<?> buildMoveRot(Map<String, Object> f) {
        return new ServerboundMovePlayerPacket.Rot(
                (float) asDouble(f.get("yaw")), (float) asDouble(f.get("pitch")),
                asBool(f.get("on_ground")), asBool(f.get("horizontal_collision")));
    }

    private static Packet<?> buildMoveStatusOnly(Map<String, Object> f) {
        return new ServerboundMovePlayerPacket.StatusOnly(
                asBool(f.get("on_ground")), asBool(f.get("horizontal_collision")));
    }

    // -----------------------------------------------------------------------
    // Swing
    // -----------------------------------------------------------------------

    private static Packet<?> buildSwing(Map<String, Object> f) {
        return new ServerboundSwingPacket(asHand(f.get("hand")));
    }

    // -----------------------------------------------------------------------
    // Player input — 7 booleans wrapped in an Input record
    // -----------------------------------------------------------------------

    private static Packet<?> buildPlayerInput(Map<String, Object> f) {
        Input in = new Input(
                asBool(f.get("forward")), asBool(f.get("backward")),
                asBool(f.get("left")), asBool(f.get("right")),
                asBool(f.get("jump")), asBool(f.get("shift")), asBool(f.get("sprint")));
        return new ServerboundPlayerInputPacket(in);
    }

    // -----------------------------------------------------------------------
    // Player command — entity_id needs Entity lookup
    // -----------------------------------------------------------------------

    private static Packet<?> buildPlayerCommand(Map<String, Object> f) {
        Entity entity = lookupEntity(asInt(f.get("entity_id")));
        if (entity == null) return null;
        ServerboundPlayerCommandPacket.Action action =
                ServerboundPlayerCommandPacket.Action.valueOf(asString(f.get("action")));
        int data = asInt(f.get("data"));
        return new ServerboundPlayerCommandPacket(entity, action, data);
    }

    // -----------------------------------------------------------------------
    // Use item — empty-hand right-click
    // -----------------------------------------------------------------------

    private static Packet<?> buildUseItem(Map<String, Object> f) {
        return new ServerboundUseItemPacket(
                asHand(f.get("hand")), asInt(f.get("sequence")),
                (float) asDouble(f.get("yaw")), (float) asDouble(f.get("pitch")));
    }

    // -----------------------------------------------------------------------
    // Use item on — pointer-rich: needs BlockHitResult(Vec3, Direction, BlockPos, inside, worldBorder)
    // -----------------------------------------------------------------------

    private static Packet<?> buildUseItemOn(Map<String, Object> f) {
        List<?> bp = asList(f.get("block_pos"));
        BlockPos blockPos = new BlockPos(asInt(bp.get(0)), asInt(bp.get(1)), asInt(bp.get(2)));
        List<?> cur = asList(f.get("cursor"));
        Vec3 location = new Vec3(asDouble(cur.get(0)), asDouble(cur.get(1)), asDouble(cur.get(2)));
        Direction face = Direction.valueOf(asString(f.get("face")));
        boolean inside = asBool(f.get("inside"));
        boolean worldBorder = asBool(f.get("world_border_hit"));
        BlockHitResult hr = new BlockHitResult(location, face, blockPos, inside, worldBorder);
        return new ServerboundUseItemOnPacket(asHand(f.get("hand")), hr, asInt(f.get("sequence")));
    }

    // -----------------------------------------------------------------------
    // Player action — dig-lifecycle + inventory edges
    // -----------------------------------------------------------------------

    private static Packet<?> buildPlayerAction(Map<String, Object> f) {
        ServerboundPlayerActionPacket.Action action =
                ServerboundPlayerActionPacket.Action.valueOf(asString(f.get("action")));
        List<?> bp = asList(f.get("block_pos"));
        BlockPos blockPos = new BlockPos(asInt(bp.get(0)), asInt(bp.get(1)), asInt(bp.get(2)));
        Direction face = Direction.valueOf(asString(f.get("face")));
        int sequence = asInt(f.get("sequence"));
        return new ServerboundPlayerActionPacket(action, blockPos, face, sequence);
    }

    // -----------------------------------------------------------------------
    // Interact — three sub-actions, static factories all need Entity
    // -----------------------------------------------------------------------

    private static Packet<?> buildInteract(Map<String, Object> f) {
        Entity entity = lookupEntity(asInt(f.get("entity_id")));
        if (entity == null) return null;
        boolean useSecondary = asBool(f.get("using_secondary_action"));
        String action = asString(f.get("action"));
        return switch (action) {
            case "ATTACK" -> ServerboundInteractPacket.createAttackPacket(entity, useSecondary);
            case "INTERACT" -> {
                InteractionHand hand = asHand(f.get("hand"));
                yield ServerboundInteractPacket.createInteractionPacket(entity, useSecondary, hand);
            }
            case "INTERACT_AT" -> {
                InteractionHand hand = asHand(f.get("hand"));
                List<?> at = asList(f.get("at"));
                Vec3 v = new Vec3(asDouble(at.get(0)), asDouble(at.get(1)), asDouble(at.get(2)));
                yield ServerboundInteractPacket.createInteractionPacket(entity, useSecondary, hand, v);
            }
            default -> null;
        };
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Resolve entity_id → Entity via the client's loaded level. */
    private static Entity lookupEntity(int entityId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return null;
        return mc.level.getEntity(entityId);
    }

    private static double asDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        throw new IllegalArgumentException("expected number, got " + describeType(o));
    }

    private static int asInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        throw new IllegalArgumentException("expected number, got " + describeType(o));
    }

    private static boolean asBool(Object o) {
        if (o instanceof Boolean b) return b;
        throw new IllegalArgumentException("expected boolean, got " + describeType(o));
    }

    private static String asString(Object o) {
        if (o instanceof String s) return s;
        throw new IllegalArgumentException("expected string, got " + describeType(o));
    }

    private static List<?> asList(Object o) {
        if (o instanceof List<?> l) return l;
        throw new IllegalArgumentException("expected list, got " + describeType(o));
    }

    private static InteractionHand asHand(Object o) {
        return InteractionHand.valueOf(asString(o));
    }

    private static String describeType(Object o) {
        return o == null ? "null" : o.getClass().getSimpleName();
    }
}
