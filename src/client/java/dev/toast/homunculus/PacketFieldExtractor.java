package dev.toast.homunculus;

import dev.toast.homunculus.mixin.ServerboundInteractPacketAccessor;
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
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured-field extraction for the 11 allowlisted serverbound packets in
 * {@link PacketAllowlist#SPATIAL_PLAY} (ml.MD §4a).
 *
 * <p>The codec's job is to map between the raw packet and a structured neural
 * action with pointer-into-observation parameters. Recording needs the raw
 * fields out of the packet so the Python codec can be developed and tested
 * against real (packet, obs) pairs offline before any neural code touches it.
 *
 * <p>Sequence numbers ({@code use_item}, {@code use_item_on},
 * {@code player_action}) are captured for completeness but flagged as plumbing
 * in ml.MD §4a — they're "generated mechanically at packet-construction; never
 * predicted." A codec that consumes these should drop them on the inference
 * side and re-generate from the local sequence counter.
 *
 * <p>Enum values are emitted as their unqualified name (e.g. {@code "MAIN_HAND"},
 * {@code "START_DESTROY_BLOCK"}) — readable in JSONL inspection and stable
 * against fabric-loom's intermediary remap.
 */
public final class PacketFieldExtractor {

    private PacketFieldExtractor() {}

    /**
     * Extract structured fields for the packet. Returns an ordered map suitable
     * for JSONL emission. Never returns null; for unimplemented types returns
     * {@code {"_unimplemented": true}}.
     */
    public static Map<String, Object> extract(Packet<?> packet) {
        if (packet instanceof ServerboundMovePlayerPacket p) return extractMove(p);
        if (packet instanceof ServerboundPlayerInputPacket p) return extractInput(p);
        if (packet instanceof ServerboundPlayerCommandPacket p) return extractCommand(p);
        if (packet instanceof ServerboundUseItemPacket p) return extractUseItem(p);
        if (packet instanceof ServerboundUseItemOnPacket p) return extractUseItemOn(p);
        if (packet instanceof ServerboundPlayerActionPacket p) return extractPlayerAction(p);
        if (packet instanceof ServerboundInteractPacket p) return extractInteract(p);
        if (packet instanceof ServerboundSwingPacket p) return extractSwing(p);
        return unimplemented();
    }

    /**
     * {@link ServerboundMovePlayerPacket} covers four wire types in 1.21.4:
     * {@code move_player_pos}, {@code move_player_pos_rot}, {@code move_player_rot},
     * {@code move_player_status_only}. {@link ServerboundMovePlayerPacket#hasPosition()} /
     * {@link ServerboundMovePlayerPacket#hasRotation()} disambiguate which
     * fields are populated on the wire.
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

    /**
     * {@link ServerboundPlayerInputPacket} wraps an {@link Input} record of 7
     * keyboard-state booleans. These are continuous-input edges (held keys) as
     * opposed to {@link ServerboundPlayerCommandPacket}'s discrete events.
     */
    private static Map<String, Object> extractInput(ServerboundPlayerInputPacket p) {
        Input in = p.input();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("forward", in.forward());
        m.put("backward", in.backward());
        m.put("left", in.left());
        m.put("right", in.right());
        m.put("jump", in.jump());
        m.put("shift", in.shift());
        m.put("sprint", in.sprint());
        return m;
    }

    /**
     * {@link ServerboundPlayerCommandPacket} encodes discrete state-edge events
     * (start/stop sprint, press/release shift, etc). {@code id} is the entity
     * id of the subject (the player, or a vehicle when riding); {@code data}
     * is an action-specific payload (zero for most actions).
     */
    private static Map<String, Object> extractCommand(ServerboundPlayerCommandPacket p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entity_id", p.getId());
        m.put("action", p.getAction().name());
        m.put("data", p.getData());
        return m;
    }

    /**
     * {@link ServerboundUseItemPacket} — right-click in empty space.
     * Carries hand + sequence + the player's reported yaw/pitch at click time
     * (server-side anti-cheat data; could legitimately be reconstructed from
     * the obs snapshot, but we record what's on the wire).
     */
    private static Map<String, Object> extractUseItem(ServerboundUseItemPacket p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hand", p.getHand().name());
        m.put("sequence", p.getSequence());
        m.put("yaw", (double) p.getYRot());
        m.put("pitch", (double) p.getXRot());
        return m;
    }

    /**
     * {@link ServerboundUseItemOnPacket} — right-click on a block (or any
     * placement action that targets a block). The pointer-rich packet: block
     * position is a pointer into the local block grid, face/cursor land the
     * placement precisely. {@code cursor} is the absolute world-space hit
     * point from {@link BlockHitResult#getLocation()}; the codec can compute
     * cursor-relative-to-block (the on-wire form) by subtracting the block pos.
     */
    private static Map<String, Object> extractUseItemOn(ServerboundUseItemOnPacket p) {
        BlockHitResult hr = p.getHitResult();
        BlockPos bp = hr.getBlockPos();
        Vec3 loc = hr.getLocation();
        Direction face = hr.getDirection();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hand", p.getHand().name());
        m.put("block_pos", List.of(bp.getX(), bp.getY(), bp.getZ()));
        m.put("face", face.name());
        m.put("cursor", List.of(loc.x, loc.y, loc.z));
        m.put("inside", hr.isInside());
        m.put("world_border_hit", hr.isWorldBorderHit());
        m.put("sequence", p.getSequence());
        return m;
    }

    /**
     * {@link ServerboundPlayerActionPacket} — break-block lifecycle
     * (START/ABORT/STOP_DESTROY_BLOCK), drop-item, release-use-item, swap-hands.
     * Pos+direction are populated for the block lifecycle actions; for non-block
     * actions they're conventionally zero.
     */
    private static Map<String, Object> extractPlayerAction(ServerboundPlayerActionPacket p) {
        BlockPos bp = p.getPos();
        Direction face = p.getDirection();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", p.getAction().name());
        m.put("block_pos", List.of(bp.getX(), bp.getY(), bp.getZ()));
        m.put("face", face.name());
        m.put("sequence", p.getSequence());
        return m;
    }

    /**
     * {@link ServerboundInteractPacket} — entity right-click / attack. Action
     * tag (ATTACK / INTERACT / INTERACT_AT) comes from the dispatch visitor;
     * entity id comes from the accessor mixin (the public {@code getTarget}
     * requires a server-side level the client doesn't have). For INTERACT_AT
     * we also record the relative hit position (e.g. saddle area vs body).
     */
    private static Map<String, Object> extractInteract(ServerboundInteractPacket p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entity_id", ((ServerboundInteractPacketAccessor) (Object) p).homunculus$entityId());
        m.put("using_secondary_action", p.isUsingSecondaryAction());
        final String[] action = {null};
        final InteractionHand[] hand = {null};
        final Vec3[] at = {null};
        p.dispatch(new ServerboundInteractPacket.Handler() {
            @Override public void onAttack() { action[0] = "ATTACK"; }
            @Override public void onInteraction(InteractionHand h) { action[0] = "INTERACT"; hand[0] = h; }
            @Override public void onInteraction(InteractionHand h, Vec3 v) { action[0] = "INTERACT_AT"; hand[0] = h; at[0] = v; }
        });
        m.put("action", action[0]);
        if (hand[0] != null) m.put("hand", hand[0].name());
        if (at[0] != null) m.put("at", List.of(at[0].x, at[0].y, at[0].z));
        return m;
    }

    /**
     * {@link ServerboundSwingPacket} — left-click arm swing. Mostly cosmetic
     * on the wire (the visual swing animation is server-broadcast), but it's
     * what KillAura's targetless click animation fires through, so it's
     * meaningful for behavioral recording.
     */
    private static Map<String, Object> extractSwing(ServerboundSwingPacket p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hand", p.getHand().name());
        return m;
    }

    private static Map<String, Object> unimplemented() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("_unimplemented", true);
        return m;
    }
}
