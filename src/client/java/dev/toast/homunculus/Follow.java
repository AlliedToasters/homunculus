package dev.toast.homunculus;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.process.IFollowProcess;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Persistent entity pursuit + drop pickup via Baritone's FollowProcess.
 *
 * <p>The old hunt path ({@code handle_hunt_passive}) sent Baritone to the mob's last-known block
 * with a single {@code GoalBlock} and waited; a fleeing mob escaped before the agent's next ~0.5s
 * LLM turn, so KillAura rarely landed the kill. FollowProcess continuously re-paths to the nearest
 * matching entity, so KillAura keeps closing into melee with no LLM turn in the loop. With
 * {@code pickup} enabled, the follow predicate also matches {@link ItemEntity} drops (Baritone paths
 * onto them → vanilla pickup) and the process's own item-pickup predicate is set — fixing the poor
 * drop-collection rate.
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
        Integer priorRadius;
        try {
            priorRadius = ClientThread.supply(() -> {
                IBaritone bar = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (bar == null) throw new IllegalStateException("no primary Baritone (player not in world?)");
                if (Minecraft.getInstance().player == null) throw new IllegalStateException("no player");

                // Items Wurst AutoDrop will reflexively re-drop. Excluding them from
                // pickup is load-bearing: without it, Baritone paths onto an autodropped
                // item → AutoDrop drops it next tick → it re-paths to the same item =
                // infinite loop (observed agent4 oscillating sapling↔feather). Single
                // source of truth: the same AutoDrop "Items" list the agent seeds at
                // startup, read live. Best-effort — empty set (pick up anything) if Wurst
                // or AutoDrop is unavailable, preserving prior behavior.
                Set<String> autodropIds = pickup ? readAutoDropItems() : Set.of();

                // Follow prey of the requested types; when picking up, also follow loose
                // ItemEntities (so Baritone walks onto drops and following() reflects them,
                // keeping the early-stop honest) — but never an item AutoDrop will re-drop.
                Predicate<Entity> followPredicate = e -> {
                    if (followTypes.contains(e.getType())) return true;
                    if (pickup && e instanceof ItemEntity ie) {
                        return !autodropIds.contains(itemId(ie.getItem()));
                    }
                    return false;
                };

                Integer prev = BaritoneAPI.getSettings().followRadius.value;
                BaritoneAPI.getSettings().followRadius.value = followRadius;
                IFollowProcess fp = bar.getFollowProcess();
                fp.follow(followPredicate);
                if (pickup) fp.pickup(stack -> !autodropIds.contains(itemId(stack)));
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
                // scan — NOT following(), which the old code leaned on and which a kill-stalled or
                // out-of-Baritone-range mob doesn't reliably populate) OR kept-item drops still
                // being followed for pickup. The window staying open is what gives KillAura time to
                // land the kill; basing it on following() alone collapsed to a ~1.6s early-stop once
                // junk ItemEntities were excluded, so KillAura never engaged.
                int activity;
                try {
                    activity = ClientThread.supply(() -> {
                        IBaritone bar = BaritoneAPI.getProvider().getPrimaryBaritone();
                        int following = bar == null ? 0 : bar.getFollowProcess().following().size();
                        return following + countNearbyPrey(followTypes, PREY_SCAN_RADIUS);
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

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** Count prey entities of {@code preyTypes} within {@code radius} of the player. Client-thread only. */
    private static int countNearbyPrey(Set<EntityType<?>> preyTypes, double radius) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return 0;
        AABB box = mc.player.getBoundingBox().inflate(radius);
        return mc.level.getEntities(mc.player, box, e -> preyTypes.contains(e.getType())).size();
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
