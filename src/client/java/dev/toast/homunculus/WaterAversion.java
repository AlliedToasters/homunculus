package dev.toast.homunculus;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reflexive water aversion. Same shape as {@link Evasion} but the trigger is
 * eye-level submergence ({@code player.isUnderWater()}) and the destination
 * (nearest dry standing block) is computed at fire-time rather than supplied
 * by the arm caller.
 *
 * <p>Motivation: Baritone's pathfinder degrades sharply once the player is
 * actually swimming — it'll oscillate, sink, or pathfind into deeper water.
 * Rather than try to tune around that, this watcher cancels whatever Baritone
 * is doing the moment the player's eye block is water and paths to the
 * nearest dry standing spot. The agent's next tool call will override the
 * flee, but by then the player is out.
 *
 * <p>Arm semantics: {@link #arm} takes no anchor (unlike Evasion). The
 * watcher just enables itself; on fire it computes the dry-land target via
 * {@link #findDryLand} (BFS-by-Manhattan over a 25×13×25 box around the
 * submerged position).
 *
 * <p>Thread model: {@link #onTick} runs on the client thread, so the BFS,
 * Baritone cancel, and goto are all done inline. HTTP arm/disarm/snapshot
 * come in on worker threads and only touch synchronized state.
 */
public final class WaterAversion {
    public enum FleeState { IDLE, IN_PROGRESS, ARRIVED, TIMEOUT, FAILED }

    private static final long FLEE_TIMEOUT_MS = 30_000;
    private static final double FLEE_ARRIVAL_TOLERANCE = 2.0;
    private static final int H_RADIUS = 12;
    private static final int V_RADIUS = 6;

    public static final WaterAversion INSTANCE = new WaterAversion();

    private volatile boolean armed = false;
    private volatile boolean fired = false;
    private volatile double[] submergedPos;   // [x, y, z] at fire moment
    private volatile int[] dryLandPos;        // [x, y, z] picked by findDryLand
    private volatile FleeState fleeState = FleeState.IDLE;
    private volatile long fleeStartedMs = 0L;
    private volatile String fleeFailureReason;

    private WaterAversion() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(INSTANCE::onTick);
    }

    /** Enable the watcher. Idempotent — re-arming clears prior fired/state but does NOT
     *  cancel an in-progress flee (the player keeps walking to dry land). */
    public synchronized void arm() {
        this.fired = false;
        this.submergedPos = null;
        this.dryLandPos = null;
        this.fleeState = FleeState.IDLE;
        this.fleeStartedMs = 0L;
        this.fleeFailureReason = null;
        this.armed = true;
    }

    public synchronized void disarm() {
        this.armed = false;
        this.fired = false;
        this.submergedPos = null;
        this.dryLandPos = null;
        this.fleeState = FleeState.IDLE;
        this.fleeStartedMs = 0L;
        this.fleeFailureReason = null;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                armed,
                fired,
                submergedPos == null ? null : submergedPos.clone(),
                dryLandPos == null ? null : dryLandPos.clone(),
                fleeState,
                fleeFailureReason);
    }

    /* ─────────────────────── tick watcher (client thread) ────────────────────── */

    private void onTick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return;
        if (!armed) return;

        if (!fired) {
            if (player.isUnderWater()) {
                boolean shouldKick;
                synchronized (this) {
                    if (armed && !fired) {
                        submergedPos = new double[] { player.getX(), player.getY(), player.getZ() };
                        fired = true;
                        shouldKick = true;
                    } else {
                        shouldKick = false;
                    }
                }
                if (shouldKick) {
                    kickFlee(player);
                }
            }
        } else if (fleeState == FleeState.IN_PROGRESS) {
            updateFleeProgress(player);
        }
    }

    /** Compute dry-land target, cancel Baritone, set a GoalBlock path. Client-thread only. */
    private void kickFlee(LocalPlayer player) {
        if (!Baritone.isApiLoaded()) {
            fleeState = FleeState.FAILED;
            fleeFailureReason = "baritone_not_loaded";
            return;
        }
        int[] target = findDryLand(player);
        if (target == null) {
            fleeState = FleeState.FAILED;
            fleeFailureReason = "no_dry_land_in_radius";
            return;
        }
        dryLandPos = target;
        IBaritone bar = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (bar == null) {
            fleeState = FleeState.FAILED;
            fleeFailureReason = "no_primary_baritone";
            return;
        }
        try {
            bar.getPathingBehavior().cancelEverything();
            bar.getBuilderProcess().onLostControl();
            bar.getMineProcess().cancel();
            bar.getCustomGoalProcess().setGoalAndPath(new GoalBlock(target[0], target[1], target[2]));
        } catch (RuntimeException e) {
            HomunculusClient.LOGGER.warn("WaterAversion kickFlee threw", e);
            fleeState = FleeState.FAILED;
            fleeFailureReason = "kick_threw: " + rootMessage(e);
            return;
        }
        fleeState = FleeState.IN_PROGRESS;
        fleeStartedMs = System.currentTimeMillis();
    }

    private void updateFleeProgress(LocalPlayer player) {
        int[] target = dryLandPos;
        if (target == null) {
            fleeState = FleeState.FAILED;
            fleeFailureReason = "target_cleared_mid_flee";
            return;
        }
        double dist = Math.abs(player.getX() - target[0])
                    + Math.abs(player.getY() - target[1])
                    + Math.abs(player.getZ() - target[2]);
        if (dist <= FLEE_ARRIVAL_TOLERANCE && !player.isUnderWater()) {
            fleeState = FleeState.ARRIVED;
            return;
        }
        if (System.currentTimeMillis() - fleeStartedMs > FLEE_TIMEOUT_MS) {
            fleeState = FleeState.TIMEOUT;
        }
    }

    /* ─────────────────────── dry-land search ────────────────────── */

    /**
     * Find the nearest "dry standing spot" — a column with a passable feet block, a
     * passable head block, a solid ground block under the feet, and no fluid at
     * feet or head. Scored by Manhattan distance with a slight vertical penalty so
     * horizontal escapes win when available.
     *
     * <p>Returns the [x, y, z] feet-position of the best candidate, or null if none
     * within radius.
     */
    private int[] findDryLand(LocalPlayer player) {
        Level level = player.level();
        int px = (int) Math.floor(player.getX());
        int py = (int) Math.floor(player.getY());
        int pz = (int) Math.floor(player.getZ());
        int[] best = null;
        double bestScore = Double.MAX_VALUE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -H_RADIUS; dx <= H_RADIUS; dx++) {
            for (int dz = -H_RADIUS; dz <= H_RADIUS; dz++) {
                for (int dy = -V_RADIUS; dy <= V_RADIUS; dy++) {
                    int x = px + dx;
                    int y = py + dy;
                    int z = pz + dz;
                    // Ground below feet must block motion and not be a fluid surface.
                    pos.set(x, y - 1, z);
                    BlockState ground = level.getBlockState(pos);
                    if (!ground.blocksMotion()) continue;
                    if (!level.getFluidState(pos).isEmpty()) continue;
                    // Feet block must be passable AND dry.
                    pos.set(x, y, z);
                    BlockState feet = level.getBlockState(pos);
                    if (feet.blocksMotion()) continue;
                    if (!level.getFluidState(pos).isEmpty()) continue;
                    // Head block must be passable AND dry.
                    pos.set(x, y + 1, z);
                    BlockState head = level.getBlockState(pos);
                    if (head.blocksMotion()) continue;
                    if (!level.getFluidState(pos).isEmpty()) continue;
                    double score = Math.abs(dx) + Math.abs(dz) + 0.6 * Math.abs(dy);
                    if (score < bestScore) {
                        bestScore = score;
                        best = new int[] { x, y, z };
                    }
                }
            }
        }
        return best;
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t.getCause() != null ? t.getCause() : t;
        String m = c.getMessage();
        return m == null ? c.getClass().getSimpleName() : m;
    }

    public record Snapshot(boolean armed, boolean fired, double[] submergedPos,
                           int[] dryLandPos, FleeState fleeState,
                           String fleeFailureReason) {}
}
