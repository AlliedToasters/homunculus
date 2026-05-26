package dev.toast.homunculus;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.process.IFollowProcess;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;

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
    private static final int EMPTY_POLLS_TO_STOP = 4; // ~1.6s with nothing to follow → done

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
        // Follow prey of the requested types; when picking up, also follow loose ItemEntities so
        // Baritone walks onto drops (and following() reflects them, keeping the early-stop honest).
        Predicate<Entity> followPredicate = e ->
                followTypes.contains(e.getType()) || (pickup && e instanceof ItemEntity);

        Integer priorRadius;
        try {
            priorRadius = ClientThread.supply(() -> {
                IBaritone bar = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (bar == null) throw new IllegalStateException("no primary Baritone (player not in world?)");
                if (Minecraft.getInstance().player == null) throw new IllegalStateException("no player");
                Integer prev = BaritoneAPI.getSettings().followRadius.value;
                BaritoneAPI.getSettings().followRadius.value = followRadius;
                IFollowProcess fp = bar.getFollowProcess();
                fp.follow(followPredicate);
                if (pickup) fp.pickup(stack -> true);
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
                int following;
                try {
                    following = ClientThread.supply(() -> {
                        IBaritone bar = BaritoneAPI.getProvider().getPrimaryBaritone();
                        return bar == null ? 0 : bar.getFollowProcess().following().size();
                    }).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    following = 0;
                }
                if (following == 0) {
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
