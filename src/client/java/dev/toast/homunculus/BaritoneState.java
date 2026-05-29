package dev.toast.homunculus;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.behavior.IPathingBehavior;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Best-effort read of Baritone's current pathing state for the obs sidecar
 * (neural_interface.md §8f {@code baritone_state}) — the execution-layer intent
 * behind most {@code move_*} packets.
 *
 * <p>Imports {@code baritone.api.*}, so this class must only be *referenced*
 * when {@link Baritone#isApiLoaded()} is true. The JVM lazy-loads it at that
 * call site, so a runtime without baritone-api never triggers a
 * {@code NoClassDefFoundError} (the same guard pattern the conditional
 * {@code /baritone/*} handler registration uses). Method signatures expose no
 * baritone types ({@code var} infers them internally) so callers don't pull in
 * the api either.
 *
 * <p>Every field read is individually try/caught: the sidecar runs every tick
 * and must degrade to a partial/null map rather than throw on the client
 * thread if an api method shifts between baritone versions.
 */
public final class BaritoneState {

    private BaritoneState() {}

    /** Null if there is no primary baritone yet; otherwise a state map. */
    public static Map<String, Object> snapshot() {
        IBaritone bar;
        try {
            bar = BaritoneAPI.getProvider().getPrimaryBaritone();
        } catch (Throwable t) {
            return null;
        }
        if (bar == null) return null;

        // Fetch the pathing behaviour once, guarded — every field read below
        // checks {@code pb != null} so a missing behaviour degrades to nulls
        // rather than throwing on the client thread (the class contract).
        IPathingBehavior pb;
        try {
            pb = bar.getPathingBehavior();
        } catch (Throwable t) {
            pb = null;
        }

        Map<String, Object> m = new LinkedHashMap<>();
        try {
            m.put("pathing", pb != null ? pb.isPathing() : null);
        } catch (Throwable t) {
            m.put("pathing", null);
        }
        // The active goal regardless of which process set it (MineProcess, Goto,
        // …). Supersedes the old getCustomGoalProcess().getGoal() read, which was
        // null during mining (mining ≠ CustomGoalProcess) — the §12.2 gap.
        try {
            var goal = pb != null ? pb.getGoal() : null;
            m.put("goal", goal == null ? null : goal.toString());
        } catch (Throwable t) {
            m.put("goal", null);
        }
        // Back-compat: the §8f CustomGoalProcess active flag (kept distinct from
        // the goal above so existing analysis still reads it).
        try {
            m.put("goal_active", bar.getCustomGoalProcess().isActive());
        } catch (Throwable t) {
            m.put("goal_active", null);
        }
        // Which process is driving — true when MineProcess (not CustomGoalProcess)
        // is steering, the common mining case.
        try {
            m.put("mine_active", bar.getMineProcess().isActive());
        } catch (Throwable t) {
            m.put("mine_active", null);
        }
        // The path target — the servo setpoint behind the move_* stream.
        // path_dest = the current segment's terminus; path_next = the immediate
        // waypoint the executor is steering toward right now (positions[idx+1]),
        // the actual driver of this tick's movement. Stored absolute; downstream
        // converts to egocentric Δ ("nothing absolute").
        m.put("path_dest", null);
        m.put("path_len", null);
        m.put("path_next", null);
        try {
            if (pb != null) {
                var op = pb.getPath();   // Optional<IPath>
                if (op.isPresent()) {
                    var p = op.get();
                    var dest = p.getDest();
                    if (dest != null) {
                        m.put("path_dest", List.of(dest.getX(), dest.getY(), dest.getZ()));
                    }
                    m.put("path_len", p.length());
                    var ex = pb.getCurrent();
                    var positions = p.positions();
                    int idx = ex == null ? 0 : ex.getPosition();
                    if (positions != null && idx + 1 < positions.size()) {
                        var n = positions.get(idx + 1);
                        m.put("path_next", List.of(n.getX(), n.getY(), n.getZ()));
                    }
                }
            }
        } catch (Throwable t) {
            // leave the pre-seeded nulls
        }
        // Motion-progress scalar: estimated ticks until the goal is reached.
        try {
            m.put("ticks_to_goal", pb != null ? pb.estimatedTicksToGoal().orElse(null) : null);
        } catch (Throwable t) {
            m.put("ticks_to_goal", null);
        }
        return m;
    }
}
