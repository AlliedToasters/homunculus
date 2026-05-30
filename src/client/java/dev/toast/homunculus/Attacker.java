package dev.toast.homunculus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Melee-attacks one specific entity, chosen by UUID. This is the injection path for the
 * neural target-selector (neural_interface.md §13.1.2): the rung-A attack-target pointer
 * picks WHICH entity to strike, and this lands the hit — KillAura's auto-aim servo is not
 * needed because we rotate + attack the chosen target ourselves.
 *
 * <p>Mirrors {@link Shearer}'s client-thread-hop discipline exactly, swapping the verb from
 * right-click ({@code gameMode.interact}) to attack ({@code gameMode.attack}). Like Shearer:
 * resolve the target on the client thread, rotate via {@link Look#faceEntity} (the server
 * validates the attacker is facing the target for hit registration), settle, then send the
 * attack + arm swing. The caller positions the player within reach first (Baritone follow /
 * arena placement). Java side only attacks.
 *
 * <p>For deterministic single-target attacks, KillAura should be OFF — otherwise KillAura
 * may also land hits on other candidates, confounding an A/B against this selector.
 */
public final class Attacker {
    private Attacker() {}

    /** Server validates attack reach at ~6 blocks (distanceToSqr < 36); stay inside it. */
    private static final double MAX_REACH = 5.0;
    /** Search box half-extent for resolving the UUID (generous; reach is checked separately). */
    private static final double SEARCH_RADIUS = 32.0;
    private static final long PER_OP_TIMEOUT_MS = 2000;
    private static final long ROT_SETTLE_MS = 50;
    private static final long ATTACK_SETTLE_MS = 150;

    public sealed interface Result permits Ok, Failure {}
    public record Ok(String uuid, String type, double distance,
                     Float healthBefore, Float healthAfter, boolean killed) implements Result {}
    public record Failure(String reason, String message) implements Result {}

    public static Result attack(String uuid) throws InterruptedException {
        Minecraft mc = Minecraft.getInstance();

        Resolved r = supply(() -> resolve(mc, uuid));
        if (r.failure != null) return r.failure;
        Entity target = r.target;
        String type = r.type;
        double distance = r.distance;
        Float healthBefore = r.healthBefore;

        Boolean rotated = supply(() -> { Look.faceEntity(mc, target); return mc.player != null; });
        if (rotated == null || !rotated) return new Failure("internal_error", "player vanished during rotate");
        Thread.sleep(ROT_SETTLE_MS);

        Failure attackFailure = supply(() -> sendAttack(mc, target));
        if (attackFailure != null) return attackFailure;
        Thread.sleep(ATTACK_SETTLE_MS);

        return supply(() -> verify(uuid, type, distance, healthBefore, target));
    }

    private record Resolved(Entity target, String type, double distance,
                            Float healthBefore, Failure failure) {
        static Resolved ok(Entity e, String t, double d, Float h) {
            return new Resolved(e, t, d, h, null);
        }
        static Resolved fail(Failure f) { return new Resolved(null, null, 0, null, f); }
    }

    private static Resolved resolve(Minecraft mc, String uuid) {
        LocalPlayer p = mc.player;
        if (p == null) return Resolved.fail(new Failure("internal_error", "no player (not in world)"));
        ClientLevel level = mc.level;
        if (level == null) return Resolved.fail(new Failure("internal_error", "no level"));
        if (mc.gameMode == null) return Resolved.fail(new Failure("internal_error", "no gameMode"));

        // Resolve the target by UUID via the shared entity highway (excludes self, nearest-first).
        List<Entity> matched = Entities.query(p, level, SEARCH_RADIUS,
                e -> e.getUUID().toString().equals(uuid));
        if (matched.isEmpty()) {
            return Resolved.fail(new Failure("entity_not_found",
                    "no entity with uuid " + uuid + " within " + SEARCH_RADIUS + " blocks"));
        }
        Entity target = matched.get(0);
        double dist = p.distanceTo(target);
        if (dist > MAX_REACH) {
            return Resolved.fail(new Failure("out_of_reach",
                    "target is " + String.format("%.2f", dist) + " blocks away (> " + MAX_REACH
                    + ") — move closer (e.g. /baritone/follow) before attacking"));
        }
        Float health = (target instanceof LivingEntity le) ? le.getHealth() : null;
        return Resolved.ok(target, Entities.typeId(target), dist, health);
    }

    private static Failure sendAttack(Minecraft mc, Entity target) {
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null) return new Failure("internal_error", "player vanished");
        if (!target.isAlive()) return new Failure("target_gone", "target died before the hit landed");
        // gameMode.attack mirrors a vanilla left-click on an entity: sends the
        // ServerboundInteractPacket(ATTACK) and applies the hit server-side. swing is the
        // arm-swing animation + packet (same call Shearer makes).
        mc.gameMode.attack(p, target);
        p.swing(InteractionHand.MAIN_HAND);
        return null;
    }

    private static Result verify(String uuid, String type, double distance,
                                 Float healthBefore, Entity target) {
        boolean killed = !target.isAlive();
        Float healthAfter = (!killed && target instanceof LivingEntity le) ? le.getHealth() : null;
        // Evidence the hit landed: target removed, OR health dropped. For non-living targets
        // we can't measure damage, so we report success on a sent attack (the packet went out).
        boolean landed = killed
                || (healthBefore != null && healthAfter != null && healthAfter < healthBefore)
                || healthBefore == null;  // non-living: best-effort
        if (!landed) {
            return new Failure("attack_no_effect",
                    "attack sent but target health unchanged — likely out of server reach, "
                    + "on i-frames (attack-cooldown), or invulnerable");
        }
        return new Ok(uuid, type, distance, healthBefore, healthAfter, killed);
    }

    private static <T> T supply(java.util.function.Supplier<T> task) {
        try {
            return ClientThread.supply(task).get(PER_OP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("client-thread op timed out", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }
}
