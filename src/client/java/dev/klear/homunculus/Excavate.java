package dev.klear.homunculus;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.utils.BlockOptionalMeta;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Synchronous excavate utility — drives Baritone's {@code IBuilderProcess.clearArea(p1, p2)} to
 * dig out an axis-aligned box and waits for the builder process to idle, then verifies the box
 * is empty via a direct world scan.
 *
 * <p>Acquires {@link Baritone#SESSION_LOCK} for the duration; returns {@link Failed} with reason
 * {@code busy} if another /baritone/* call is in flight.
 *
 * <p>Snapshots and restores Baritone build settings ({@code buildIgnoreBlocks},
 * {@code buildInLayers}, {@code layerOrder}) before/after the dig — they're global so the lock
 * guarantees serial access. On any terminal state, calls {@code pathingBehavior.cancelEverything()}
 * before releasing the lock.
 *
 * <p>Builder completion is detected via {@code IBuilderProcess.isActive()} flipping false after
 * a prior active state — {@code PathEvent}s are deliberately ignored because the builder may
 * emit {@code CALC_FAILED} for individual unreachable blocks while still making progress on
 * others. The post-scan block count is authoritative: zero remaining = success ({@code cleared}),
 * non-zero = partial ({@code partial}).
 */
public final class Excavate {
    private Excavate() {}

    public static final long HARD_CAP_SECONDS = 600;
    public static final long DEFAULT_TIMEOUT_SECONDS = 120;
    public static final int MAX_VOLUME = 500;
    private static final long GAME_THREAD_TIMEOUT_MS = 5_000;
    private static final long DEFAULT_START_WINDOW_SECONDS = 15;
    private static final long BOM_PREWARM_TIMEOUT_MS = 30_000;

    /** Blocks the builder will leave in place (skipped during clear). Matches the Reddit
     * "buildIgnoreBlocks torch" recipe — preserves player-placed lighting if the caller is
     * re-clearing an existing shelter. Soul variants included for parity. */
    private static final List<Block> IGNORED_BLOCKS = List.of(
            Blocks.TORCH, Blocks.WALL_TORCH, Blocks.SOUL_TORCH, Blocks.SOUL_WALL_TORCH);

    public sealed interface Outcome permits Cleared, Failed {
        int[] box();
        int volume();
        int remaining();
    }
    public record Cleared(String reason, String message, int[] box, int volume, int remaining) implements Outcome {}
    public record Failed(String reason, String message, int[] box, int volume, int remaining) implements Outcome {}

    public static Outcome run(int x1, int y1, int z1, int x2, int y2, int z2, long timeoutSeconds) {
        int[] box = normalize(x1, y1, z1, x2, y2, z2);
        int volume = (box[3] - box[0] + 1) * (box[4] - box[1] + 1) * (box[5] - box[2] + 1);

        if (!Baritone.isApiLoaded()) {
            return new Failed("baritone_not_loaded",
                    "Baritone API not present at runtime", box, volume, 0);
        }
        if (volume > MAX_VOLUME) {
            return new Failed("invalid_request",
                    "volume " + volume + " exceeds cap " + MAX_VOLUME, box, volume, 0);
        }
        if (!Baritone.SESSION_LOCK.tryLock()) {
            return new Failed("busy",
                    "another /baritone/* call is in flight", box, volume, 0);
        }
        try {
            return runLocked(box, volume, timeoutSeconds);
        } finally {
            Baritone.SESSION_LOCK.unlock();
        }
    }

    private static Outcome runLocked(int[] box, int volume, long timeoutSeconds) {
        // BOM(air) prewarm. clearArea internally builds a FillSchematic backed by a
        // BlockOptionalMeta(AIR) — first construction of that BOM on the game thread can deadlock
        // on the registry future. Build it off-thread first to populate the drops cache; the
        // subsequent on-thread construction inside clearArea then hits the cache.
        // See memory: baritone_mine_deadlock.
        try {
            CompletableFuture
                    .supplyAsync(() -> new BlockOptionalMeta(Blocks.AIR))
                    .get(BOM_PREWARM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return new Failed("internal_error",
                    "BOM(air) prewarm timed out after " + BOM_PREWARM_TIMEOUT_MS + "ms",
                    box, volume, 0);
        } catch (Exception e) {
            return new Failed("internal_error",
                    "BOM(air) prewarm threw: " + rootMessage(e), box, volume, 0);
        }

        int preCount;
        try {
            preCount = countRemaining(box);
        } catch (Exception e) {
            return new Failed("internal_error",
                    "pre-scan threw: " + rootMessage(e), box, volume, 0);
        }
        if (preCount == 0) {
            return new Cleared("already_clear",
                    "box already contains no blocks to break", box, volume, 0);
        }

        Snapshot snapshot;
        try {
            snapshot = applyOverrides();
        } catch (Exception e) {
            return new Failed("internal_error",
                    "failed to apply build settings: " + rootMessage(e), box, volume, preCount);
        }

        LinkedBlockingQueue<Signal> queue = new LinkedBlockingQueue<>();
        AtomicBoolean closed = new AtomicBoolean(false);

        AbstractGameEventListener listener = new AbstractGameEventListener() {
            @Override public void onTick(TickEvent event) {
                if (closed.get()) return;
                IBaritone bar = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (bar == null) return;
                queue.offer(new TickSignal(bar.getBuilderProcess().isActive()));
            }
        };

        try {
            ClientThread.supply(() -> {
                IBaritone bar = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (bar == null) throw new IllegalStateException("no primary Baritone (player not in world?)");
                bar.getGameEventHandler().registerEventListener(listener);
                bar.getBuilderProcess().clearArea(
                        new BlockPos(box[0], box[1], box[2]),
                        new BlockPos(box[3], box[4], box[5]));
                return null;
            }).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            closed.set(true);
            restoreOverrides(snapshot);
            return new Failed("internal_error",
                    "failed to start excavate: " + rootMessage(e), box, volume, preCount);
        }

        long now = System.currentTimeMillis();
        long startDeadline = now + DEFAULT_START_WINDOW_SECONDS * 1000L;
        long timeoutMs = Math.min(timeoutSeconds, HARD_CAP_SECONDS) * 1000L;
        long deadline = now + timeoutMs;
        boolean wentActive = false;
        int lastRemaining = preCount;

        try {
            while (true) {
                long t = System.currentTimeMillis();
                if (t >= deadline) {
                    int remaining = safeRemaining(box, lastRemaining);
                    return new Failed(wentActive ? "timeout" : "never_started",
                            wentActive
                                    ? "deadline elapsed; " + remaining + " of " + volume + " blocks unbroken"
                                    : "start window elapsed without builderProcess going active",
                            box, volume, remaining);
                }
                if (!wentActive && t >= startDeadline) {
                    int remaining = safeRemaining(box, lastRemaining);
                    return new Failed("never_started",
                            "start window elapsed without builderProcess going active",
                            box, volume, remaining);
                }
                long wait = (wentActive ? deadline : Math.min(deadline, startDeadline)) - t;
                Signal sig = queue.poll(wait, TimeUnit.MILLISECONDS);
                if (sig == null) continue;

                if (sig instanceof TickSignal ts) {
                    if (ts.active) {
                        wentActive = true;
                    } else if (wentActive) {
                        int remaining = safeRemaining(box, lastRemaining);
                        if (remaining == 0) {
                            return new Cleared("cleared",
                                    "excavate completed; " + volume + " blocks cleared",
                                    box, volume, 0);
                        }
                        return new Failed("partial",
                                "builder idled with " + remaining + " of " + volume + " blocks unbroken",
                                box, volume, remaining);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Failed("internal_error", "interrupted", box, volume, lastRemaining);
        } finally {
            closed.set(true);
            try {
                ClientThread.supply(() -> {
                    IBaritone bar = BaritoneAPI.getProvider().getPrimaryBaritone();
                    if (bar != null) bar.getPathingBehavior().cancelEverything();
                    return null;
                }).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                HomunculusClient.LOGGER.warn("Excavate cleanup cancelEverything threw", e);
            }
            restoreOverrides(snapshot);
        }
    }

    private static int safeRemaining(int[] box, int fallback) {
        try {
            return countRemaining(box);
        } catch (Exception e) {
            HomunculusClient.LOGGER.warn("Excavate post-scan threw", e);
            return fallback;
        }
    }

    private static int countRemaining(int[] box) throws Exception {
        return ClientThread.supply(() -> {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level == null) throw new IllegalStateException("no client level");
            int count = 0;
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (int y = box[1]; y <= box[4]; y++) {
                for (int z = box[2]; z <= box[5]; z++) {
                    for (int x = box[0]; x <= box[3]; x++) {
                        cursor.set(x, y, z);
                        BlockState bs = level.getBlockState(cursor);
                        if (bs.isAir()) continue;
                        if (IGNORED_BLOCKS.contains(bs.getBlock())) continue;
                        count++;
                    }
                }
            }
            return count;
        }).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private record Snapshot(
            List<Block> priorBuildIgnoreBlocks,
            Boolean priorBuildInLayers,
            Boolean priorLayerOrder) {}

    private static Snapshot applyOverrides() throws Exception {
        return ClientThread.supply(() -> {
            List<Block> priorIgnore = new ArrayList<>(BaritoneAPI.getSettings().buildIgnoreBlocks.value);
            Boolean priorLayers = BaritoneAPI.getSettings().buildInLayers.value;
            Boolean priorOrder = BaritoneAPI.getSettings().layerOrder.value;
            BaritoneAPI.getSettings().buildIgnoreBlocks.value = new ArrayList<>(IGNORED_BLOCKS);
            BaritoneAPI.getSettings().buildInLayers.value = true;
            BaritoneAPI.getSettings().layerOrder.value = true;
            return new Snapshot(priorIgnore, priorLayers, priorOrder);
        }).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private static void restoreOverrides(Snapshot s) {
        if (s == null) return;
        try {
            ClientThread.supply(() -> {
                BaritoneAPI.getSettings().buildIgnoreBlocks.value = s.priorBuildIgnoreBlocks();
                BaritoneAPI.getSettings().buildInLayers.value = s.priorBuildInLayers();
                BaritoneAPI.getSettings().layerOrder.value = s.priorLayerOrder();
                return null;
            }).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            HomunculusClient.LOGGER.warn("Excavate failed to restore Baritone build settings", e);
        }
    }

    private static int[] normalize(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new int[] {
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2)
        };
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t.getCause() != null ? t.getCause() : t;
        String m = c.getMessage();
        return m == null ? c.getClass().getSimpleName() : m;
    }

    private sealed interface Signal {}
    private record TickSignal(boolean active) implements Signal {}
}
