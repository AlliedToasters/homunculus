package dev.toast.homunculus;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.process.IFollowProcess;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Persistent entity pursuit + drop pickup via Baritone's FollowProcess.
 *
 * <p>The old hunt path ({@code handle_hunt_passive}) sent Baritone to the mob's last-known block
 * with a single {@code GoalBlock} and waited; a fleeing mob escaped before the agent's next ~0.5s
 * LLM turn, so KillAura rarely landed the kill. FollowProcess continuously re-paths to the nearest
 * matching entity, so KillAura keeps closing into melee with no LLM turn in the loop.
 *
 * <p><b>Phased follow.</b> FollowProcess pursues the single <i>nearest</i> entity its predicate
 * accepts — it can't be told to prefer one kind over another. So the predicate is phased: while any
 * live prey is in range it accepts prey only; once prey clears it switches to accepting loose item
 * drops (the post-hunt sweep). A flat "prey OR items" predicate let a ground drop nearer than the
 * mob win the nearest-target race, parking the player on the item while KillAura never reached a
 * mob (observed: failed hunts moved ~4 blocks vs ~39 on a successful chase). Vanilla ~1-block
 * auto-pickup still grabs drops trampled during the chase, so deferring the deliberate item-sweep
 * until prey clears costs ~nothing. Entity scans go through {@link Entities}.
 *
 * <p>Acquires {@link Baritone#SESSION_LOCK} (returns {@code busy} if another /baritone/* call holds
 * it). Snapshots+restores {@code followRadius}. Cancels FollowProcess + pathing on exit via
 * try/finally. Blocks up to {@code durationSeconds}, stopping early once nothing matches for a short
 * window (prey killed + drops collected) so a quick hunt doesn't burn the full budget.
 */
public final class Follow {
    private Follow() {}

    public static final long HARD_CAP_SECONDS = 60;
    private static final long GAME_THREAD_TIMEOUT_MS = 5_000;
    private static final long POLL_MS = 400;
    private static final int EMPTY_POLLS_TO_STOP = 4; // ~1.6s with nothing to do → done
    // Horizontal+vertical reach for the "is there still prey to hunt?" scan that drives the
    // early-stop. Generous so the window stays open while a reachable mob is anywhere in the
    // engagement area (KillAura needs the player loitering for several seconds to land a kill).
    private static final double PREY_SCAN_RADIUS = 24.0;

    public record Result(boolean ok, String reason, String message) {}

    public static Result run(Set<EntityType<?>> followTypes, boolean pickup,
                             long durationSeconds, int followRadius) {
        if (!Baritone.isApiLoaded()) {
            return new Result(false, "baritone_not_loaded", "Baritone API not present at runtime");
        }
        if (!Baritone.SESSION_LOCK.tryLock()) {
            return new Result(false, "busy", "another /baritone/* call is in flight");
        }
        try {
            return runLocked(followTypes, pickup, durationSeconds, followRadius);
        } finally {
            Baritone.SESSION_LOCK.unlock();
        }
    }

    private static Result runLocked(Set<EntityType<?>> followTypes, boolean pickup,
                                    long durationSeconds, int followRadius) {
        // Live phase flag, flipped by the poll loop. false = hunt prey (ignore items); true =
        // sweep drops (no live prey left). Read inside the FollowProcess predicate, which Baritone
        // re-evaluates every tick on the client thread, so a flip takes effect within one poll.
        final AtomicBoolean sweepItems = new AtomicBoolean(false);
        final Predicate<Entity> preyPred = Entities.ofTypes(followTypes);

        Integer priorRadius;
        try {
            priorRadius = ClientThread.supply(() -> {
                IBaritone bar = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (bar == null) throw new IllegalStateException("no primary Baritone (player not in world?)");
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null || mc.level == null) throw new IllegalStateException("no player");

                // Items Wurst AutoDrop will reflexively re-drop. Excluding them from
                // pickup is load-bearing: without it, Baritone paths onto an autodropped
                // item → AutoDrop drops it next tick → it re-paths to the same item =
                // infinite loop (observed agent4 oscillating sapling↔feather). Single
                // source of truth: the same AutoDrop "Items" list the agent seeds at
                // startup, read live. Best-effort — empty set (pick up anything) if Wurst
                // or AutoDrop is unavailable, preserving prior behavior.
                Set<String> autodropIds = pickup ? readAutoDropItems() : Set.of();

                // Phased predicate (see class doc): prey always matches; loose item drops match
                // only once sweepItems flips true (no live prey left), so a stray drop never
                // out-competes a mob for FollowProcess's nearest-target pick.
                Predicate<Entity> itemPred = pickup ? Entities.looseItems(autodropIds) : e -> false;
                Predicate<Entity> followPredicate =
                        e -> preyPred.test(e) || (sweepItems.get() && itemPred.test(e));

                // Seed the phase: if there's no prey to hunt right now (pure-gather call, or the
                // herd already moved off), go straight to sweep so we still act on drops.
                sweepItems.set(pickup
                        && Entities.count(mc.player, mc.level, PREY_SCAN_RADIUS, preyPred) == 0);

                Integer prev = BaritoneAPI.getSettings().followRadius.value;
                BaritoneAPI.getSettings().followRadius.value = followRadius;
                IFollowProcess fp = bar.getFollowProcess();
                fp.follow(followPredicate);
                if (pickup) fp.pickup(stack -> !autodropIds.contains(Entities.itemId(stack)));
                return prev;
            }).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            cancelQuietly();
            return new Result(false, "internal_error", "failed to start follow: " + rootMessage(e));
        }

        long deadline = System.currentTimeMillis() + Math.min(durationSeconds, HARD_CAP_SECONDS) * 1000L;
        int emptyPolls = 0;
        String reason = "completed";
        try {
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_MS);
                // Keep the window open while there's still something to do: prey nearby (a fresh
                // scan — NOT following(), which a kill-stalled or out-of-Baritone-range mob doesn't
                // reliably populate) OR kept-item drops still being followed during the sweep phase.
                // The window staying open is what gives KillAura time to land the kill.
                int activity;
                try {
                    activity = ClientThread.supply(() -> {
                        IBaritone bar = BaritoneAPI.getProvider().getPrimaryBaritone();
                        int following = bar == null ? 0 : bar.getFollowProcess().following().size();
                        Minecraft mc = Minecraft.getInstance();
                        int prey = (mc.player == null || mc.level == null) ? 0
                                : Entities.count(mc.player, mc.level, PREY_SCAN_RADIUS, preyPred);
                        // Phase transition: hunt while any prey remains; once cleared, sweep drops.
                        if (pickup) sweepItems.set(prey == 0);
                        return following + prey;
                    }).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    activity = 0;
                }
                if (activity == 0) {
                    if (++emptyPolls >= EMPTY_POLLS_TO_STOP) { reason = "targets_cleared"; break; }
                } else {
                    emptyPolls = 0;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reason = "interrupted";
        } finally {
            final Integer pr = priorRadius;
            try {
                ClientThread.supply(() -> {
                    IBaritone bar = BaritoneAPI.getProvider().getPrimaryBaritone();
                    if (bar != null) {
                        bar.getFollowProcess().cancel();
                        bar.getPathingBehavior().cancelEverything();
                    }
                    if (pr != null) BaritoneAPI.getSettings().followRadius.value = pr;
                    return null;
                }).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                HomunculusClient.LOGGER.warn("Follow cleanup threw", e);
            }
        }
        return new Result(true, reason, "follow ran (" + reason + ")");
    }

    /**
     * The live Wurst AutoDrop "Items" list (ids AutoDrop will re-drop), or an empty set if Wurst /
     * AutoDrop / the setting reflection isn't available. Must be called on the client thread.
     */
    private static Set<String> readAutoDropItems() {
        try {
            if (!Wurst.isApiLoaded() || !Wurst.isSettingApiReady()) return Set.of();
            Object hack = Wurst.findHack("AutoDrop");
            if (hack == null) return Set.of();
            Object setting = Wurst.findSetting(hack, "Items");
            if (setting == null || !Wurst.isItemListSetting(setting)) return Set.of();
            Set<String> ids = new HashSet<>(Wurst.getItemNames(setting));
            HomunculusClient.LOGGER.info("Follow: excluding {} AutoDrop item(s) from pickup", ids.size());
            return ids;
        } catch (Exception e) {
            HomunculusClient.LOGGER.warn("Follow: could not read AutoDrop list ({}); pickup unfiltered",
                    rootMessage(e));
            return Set.of();
        }
    }

    private static void cancelQuietly() {
        try {
            ClientThread.supply(() -> {
                IBaritone bar = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (bar != null) {
                    bar.getFollowProcess().cancel();
                    bar.getPathingBehavior().cancelEverything();
                }
                return null;
            }).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t.getCause() != null ? t.getCause() : t;
        String m = c.getMessage();
        return m == null ? c.getClass().getSimpleName() : m;
    }
}
