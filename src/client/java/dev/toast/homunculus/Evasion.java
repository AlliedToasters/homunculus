package dev.toast.homunculus;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reflexive hostile-mob evasion. Single chokepoint, no per-handler plumbing.
 *
 * <p>Callers (e.g. the Python agent loop) {@link #arm} the watcher once per turn with a
 * "go back here if attacked" anchor; on the first hostile-mob hit the watcher itself —
 * not the active handler — cancels Baritone and sets a goto path back to the anchor.
 * Whatever baritone task was in flight gets cancelled implicitly. Handlers don't know
 * Evasion exists.
 *
 * <p>State surfaced via {@link #snapshot} so the HTTP layer can answer
 * {@code GET /evasion/status}: armed, fired, anchor, attackers, and a flee state
 * machine ({@code idle → in_progress → arrived|timeout|failed}).
 *
 * <p>Thread model: state mutation is synchronized on the singleton. The watcher
 * ({@link ClientTickEvents#END_CLIENT_TICK}) runs on the client thread, so the
 * cancel-and-reroute can be done inline without {@code ClientThread.supply}. The
 * HTTP arm/disarm/snapshot calls come in on worker threads and only touch the
 * synchronized state.
 *
 * <p>Hostile filter: {@code entity.getType().getCategory() == MobCategory.MONSTER}.
 * Covers zombies, skeletons, creepers, spiders, drowned, husks, witches, endermen.
 * Player damage, fall damage, lava, suffocation are intentionally not evasion triggers
 * — the agent's existing primitives handle those (lava → surface, HP-low → shelter).
 */
public final class Evasion {
    public enum FleeState { IDLE, IN_PROGRESS, ARRIVED, TIMEOUT, FAILED }

    private static final long FLEE_TIMEOUT_MS = 60_000;
    // Anchor arrival tolerance — Baritone often lands slightly off the requested column.
    private static final double FLEE_ARRIVAL_TOLERANCE = 3.0;

    public static final Evasion INSTANCE = new Evasion();

    private volatile boolean armed = false;
    private volatile boolean fired = false;
    private volatile double[] anchor;          // [x, y, z]
    private final Set<String> attackers = new LinkedHashSet<>();
    private volatile FleeState fleeState = FleeState.IDLE;
    private volatile long fleeStartedMs = 0L;
    private volatile String fleeFailureReason;

    // Damage watcher state — client-thread only.
    private LocalPlayer trackedPlayer;
    private int priorHurtTime;

    private Evasion() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(INSTANCE::onTick);
    }

    /**
     * Arm the watcher with a turn-start anchor. Subsequent hostile-mob hits will set
     * {@link #fired} = true, record the attacker type, and autonomously cancel-and-flee.
     * Idempotent re-arm replaces the prior anchor (most recent arm wins).
     */
    public synchronized void arm(double[] anchor) {
        this.anchor = anchor == null ? null : anchor.clone();
        this.fired = false;
        this.attackers.clear();
        this.fleeState = FleeState.IDLE;
        this.fleeStartedMs = 0L;
        this.fleeFailureReason = null;
        this.armed = true;
    }

    /** Clear state. Does NOT cancel an in-progress flee — the player keeps walking;
     *  whatever the next baritone task is will override the flee path. */
    public synchronized void disarm() {
        this.armed = false;
        this.fired = false;
        this.anchor = null;
        this.attackers.clear();
        this.fleeState = FleeState.IDLE;
        this.fleeStartedMs = 0L;
        this.fleeFailureReason = null;
    }

    /** Snapshot of current state for the HTTP status endpoint. */
    public synchronized Snapshot snapshot() {
        return new Snapshot(
                armed,
                fired,
                anchor == null ? null : anchor.clone(),
                List.copyOf(attackers),
                fleeState,
                fleeFailureReason);
    }

    /* ─────────────────────── damage watcher (client thread) ────────────────────── */

    private void onTick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) {
            trackedPlayer = null;
            priorHurtTime = 0;
            return;
        }
        if (player != trackedPlayer) {
            trackedPlayer = player;
            priorHurtTime = player.hurtTime;
            return;
        }

        // First-fire edge: hurtTime jumps up on a fresh damage event (Mojang's
        // LivingEntity.actuallyHurt sets it to maxHurtTime, default 10). Trigger
        // on any increase so we catch the first frame even if subsequent ticks
        // reduce it.
        int hurtTime = player.hurtTime;
        if (hurtTime > priorHurtTime) {
            DamageSource src = player.getLastDamageSource();
            if (src != null && isHostileMobAttack(src)) {
                String attackerId = describeAttacker(src);
                boolean shouldKick = false;
                synchronized (this) {
                    if (armed && !fired) {
                        attackers.add(attackerId);
                        fired = true;
                        shouldKick = true;
                    } else if (armed) {
                        // Already fired this arm window; just record the additional attacker.
                        attackers.add(attackerId);
                    }
                }
                if (shouldKick) {
                    kickFlee(player);
                }
            }
        }
        priorHurtTime = hurtTime;

        // Progress the flee state machine if we're in the middle of one.
        if (fleeState == FleeState.IN_PROGRESS) {
            updateFleeProgress(player);
        }
    }

    /** Cancel whatever Baritone is doing and start pathing back to anchor.
     *  Runs on the client thread — caller is the tick handler. */
    private void kickFlee(LocalPlayer player) {
        double[] target = anchor;
        if (target == null) {
            fleeState = FleeState.FAILED;
            fleeFailureReason = "no_anchor";
            return;
        }
        if (!Baritone.isApiLoaded()) {
            fleeState = FleeState.FAILED;
            fleeFailureReason = "baritone_not_loaded";
            return;
        }
        IBaritone bar = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (bar == null) {
            fleeState = FleeState.FAILED;
            fleeFailureReason = "no_primary_baritone";
            return;
        }
        int tx = (int) Math.floor(target[0]);
        int ty = (int) Math.floor(target[1]);
        int tz = (int) Math.floor(target[2]);
        try {
            // Whatever the active handler was doing, stop it. cancelEverything cascades
            // to all baritone processes; the explicit builder/mine cancels are belt-and-
            // suspenders for the processes that don't always honor cancelEverything.
            bar.getPathingBehavior().cancelEverything();
            bar.getBuilderProcess().onLostControl();
            bar.getMineProcess().cancel();
            bar.getCustomGoalProcess().setGoalAndPath(new GoalBlock(tx, ty, tz));
        } catch (RuntimeException e) {
            HomunculusClient.LOGGER.warn("Evasion kickFlee threw", e);
            fleeState = FleeState.FAILED;
            fleeFailureReason = "kick_threw: " + rootMessage(e);
            return;
        }
        fleeState = FleeState.IN_PROGRESS;
        fleeStartedMs = System.currentTimeMillis();
    }

    private void updateFleeProgress(LocalPlayer player) {
        double[] target = anchor;
        if (target == null) {
            fleeState = FleeState.FAILED;
            fleeFailureReason = "anchor_cleared_mid_flee";
            return;
        }
        double dist = Math.abs(player.getX() - target[0])
                    + Math.abs(player.getY() - target[1])
                    + Math.abs(player.getZ() - target[2]);
        if (dist <= FLEE_ARRIVAL_TOLERANCE) {
            fleeState = FleeState.ARRIVED;
            return;
        }
        if (System.currentTimeMillis() - fleeStartedMs > FLEE_TIMEOUT_MS) {
            fleeState = FleeState.TIMEOUT;
        }
    }

    private static boolean isHostileMobAttack(DamageSource src) {
        Entity attacker = src.getEntity();
        if (attacker == null) return false;
        if (attacker instanceof Player) return false;  // ignore PvP for evasion purposes
        MobCategory cat = attacker.getType().getCategory();
        return cat == MobCategory.MONSTER;
    }

    private static String describeAttacker(DamageSource src) {
        Entity attacker = src.getEntity();
        if (attacker == null) return "unknown";
        return BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()).toString();
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t.getCause() != null ? t.getCause() : t;
        String m = c.getMessage();
        return m == null ? c.getClass().getSimpleName() : m;
    }

    /** Immutable snapshot returned to {@code /evasion/status}. */
    public record Snapshot(boolean armed, boolean fired, double[] anchor,
                           List<String> attackers, FleeState fleeState,
                           String fleeFailureReason) {}
}
