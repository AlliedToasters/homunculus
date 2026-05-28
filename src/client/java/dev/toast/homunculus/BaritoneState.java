package dev.toast.homunculus;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;

import java.util.LinkedHashMap;
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

        Map<String, Object> m = new LinkedHashMap<>();
        try {
            m.put("pathing", bar.getPathingBehavior().isPathing());
        } catch (Throwable t) {
            m.put("pathing", null);
        }
        try {
            var gp = bar.getCustomGoalProcess();
            m.put("goal_active", gp.isActive());
            var goal = gp.getGoal();
            m.put("goal", goal == null ? null : goal.toString());
        } catch (Throwable t) {
            m.put("goal_active", null);
            m.put("goal", null);
        }
        return m;
    }
}
