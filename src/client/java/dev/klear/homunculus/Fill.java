package dev.klear.homunculus;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.schematic.FillSchematic;
import baritone.api.utils.BlockOptionalMeta;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Synchronous fill utility — drives Baritone's {@code IBuilderProcess.build(name, FillSchematic, origin)}
 * to place a target block at every air cell in an axis-aligned box, leaving existing solid blocks
 * alone (via {@code buildIgnoreExisting=true}).
 *
 * <p>Acquires {@link Baritone#SESSION_LOCK} for the duration; returns {@link Failed} with reason
 * {@code busy} if another /baritone/* call is in flight.
 *
 * <p>Pre-flight: requires at least one stack of the target block in the player's hotbar — Baritone
 * cannot reach into main inventory for placement blocks, so an inventory-only stack would silently
 * starve the build. Caller is expected to {@code /equip} before {@code /baritone/fill}.
 *
 * <p>Snapshots/restores {@code buildIgnoreBlocks}, {@code buildInLayers}, {@code layerOrder},
 * {@code buildIgnoreExisting}. Completion is detected via {@code IBuilderProcess.isActive()}
 * flipping false after a prior active state; remaining-air count is authoritative (zero = success,
 * non-zero = {@code partial}).
 */
public final class Fill {
    private Fill() {}

    public static final long HARD_CAP_SECONDS = 600;
    public static final long DEFAULT_TIMEOUT_SECONDS = 120;
    public static final int MAX_VOLUME = 500;
    private static final long GAME_THREAD_TIMEOUT_MS = 5_000;
    private static final long DEFAULT_START_WINDOW_SECONDS = 15;
    private static final long BOM_PREWARM_TIMEOUT_MS = 30_000;
    // Stuck watchdog: while builder is active+unpaused, sample air-cell count
    // every PROGRESS_CHECK_INTERVAL_MS; if no decrease for STUCK_THRESHOLD_MS,
    // bail with "stuck". Catches the BuilderProcess-thrashing case where path
    // calc fails repeatedly (e.g., placement target has no anchor neighbor),
    // PathExecutor self-cancels, builder re-plans, same failure — isActive
    // stays true and isPaused stays false, so neither existing exit fires.
    private static final long PROGRESS_CHECK_INTERVAL_MS = 2_000;
    private static final long STUCK_THRESHOLD_MS = 20_000;

    public sealed interface Outcome permits Filled, Failed {
        int[] box();
        int volume();
        int remaining();
        String block();
    }
    public record Filled(String reason, String message, int[] box, int volume, int remaining, String block) implements Outcome {}
    public record Failed(String reason, String message, int[] box, int volume, int remaining, String block) implements Outcome {}

    public static Outcome run(String blockId, Block block,
                              int x1, int y1, int z1, int x2, int y2, int z2,
                              long timeoutSeconds) {
        int[] box = normalize(x1, y1, z1, x2, y2, z2);
        int volume = (box[3] - box[0] + 1) * (box[4] - box[1] + 1) * (box[5] - box[2] + 1);

        if (!Baritone.isApiLoaded()) {
            return new Failed("baritone_not_loaded",
                    "Baritone API not present at runtime", box, volume, 0, blockId);
        }
        if (volume > MAX_VOLUME) {
            return new Failed("invalid_request",
                    "volume " + volume + " exceeds cap " + MAX_VOLUME, box, volume, 0, blockId);
        }
        Item fillItem = block.asItem();
        if (fillItem == net.minecraft.world.item.Items.AIR && block != net.minecraft.world.level.block.Blocks.AIR) {
            return new Failed("invalid_request",
                    "block '" + blockId + "' has no item form and cannot be placed",
                    box, volume, 0, blockId);
        }
        if (!Baritone.SESSION_LOCK.tryLock()) {
            return new Failed("busy",
                    "another /baritone/* call is in flight", box, volume, 0, blockId);
        }
        try {
            return runLocked(blockId, block, fillItem, box, volume, timeoutSeconds);
        } finally {
            Baritone.SESSION_LOCK.unlock();
        }
    }

    private static Outcome runLocked(String blockId, Block block, Item fillItem,
                                     int[] box, int volume, long timeoutSeconds) {
        // BOM(target) prewarm — same rationale as Excavate's BOM(air): FillSchematic holds a BOM
        // that's first-touched during the build kickoff on the game thread, which can deadlock on
        // the registry future if the BOM was never constructed before. See memory:
        // baritone_mine_deadlock.
        BlockOptionalMeta targetBom;
        try {
            targetBom = CompletableFuture
                    .supplyAsync(() -> new BlockOptionalMeta(block))
                    .get(BOM_PREWARM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return new Failed("internal_error",
                    "BOM(" + blockId + ") prewarm timed out after " + BOM_PREWARM_TIMEOUT_MS + "ms",
                    box, volume, 0, blockId);
        } catch (Exception e) {
            return new Failed("internal_error",
                    "BOM(" + blockId + ") prewarm threw: " + rootMessage(e),
                    box, volume, 0, blockId);
        }

        try {
            if (!hasFillBlockInHotbar(fillItem)) {
                return new Failed("missing_block",
                        "fill block '" + blockId + "' not present in hotbar (Baritone can't reach main inventory)",
                        box, volume, 0, blockId);
            }
        } catch (Exception e) {
            return new Failed("internal_error",
                    "hotbar check threw: " + rootMessage(e), box, volume, 0, blockId);
        }

        int preAir;
        try {
            preAir = countAir(box);
        } catch (Exception e) {
            return new Failed("internal_error",
                    "pre-scan threw: " + rootMessage(e), box, volume, 0, blockId);
        }
        if (preAir == 0) {
            return new Filled("already_filled",
                    "box contains no air cells to fill", box, volume, 0, blockId);
        }

        Snapshot snapshot;
        try {
            snapshot = applyOverrides();
        } catch (Exception e) {
            return new Failed("internal_error",
                    "failed to apply build settings: " + rootMessage(e),
                    box, volume, preAir, blockId);
        }

        LinkedBlockingQueue<Signal> queue = new LinkedBlockingQueue<>();
        AtomicBoolean closed = new AtomicBoolean(false);

        AbstractGameEventListener listener = new AbstractGameEventListener() {
            @Override public void onTick(TickEvent event) {
                if (closed.get()) return;
                IBaritone bar = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (bar == null) return;
                var bp = bar.getBuilderProcess();
                queue.offer(new TickSignal(bp.isActive(), bp.isPaused()));
            }
        };

        int w = box[3] - box[0] + 1;
        int h = box[4] - box[1] + 1;
        int d = box[5] - box[2] + 1;
        FillSchematic schem = new FillSchematic(w, h, d, targetBom);
        Vec3i origin = new Vec3i(box[0], box[1], box[2]);

        try {
            ClientThread.supply(() -> {
                IBaritone bar = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (bar == null) throw new IllegalStateException("no primary Baritone (player not in world?)");
                bar.getGameEventHandler().registerEventListener(listener);
                bar.getBuilderProcess().build("homunculus-fill", schem, origin);
                return null;
            }).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            closed.set(true);
            restoreOverrides(snapshot);
            return new Failed("internal_error",
                    "failed to start fill: " + rootMessage(e),
                    box, volume, preAir, blockId);
        }

        long now = System.currentTimeMillis();
        long startDeadline = now + DEFAULT_START_WINDOW_SECONDS * 1000L;
        long timeoutMs = Math.min(timeoutSeconds, HARD_CAP_SECONDS) * 1000L;
        long deadline = now + timeoutMs;
        boolean wentActive = false;
        int lastRemaining = preAir;
        // Watchdog state. lastProgressMs resets whenever the air count
        // strictly decreases; if it stays put past STUCK_THRESHOLD_MS we
        // declare the build stuck even though Baritone still reports active.
        long lastProgressMs = now;
        long nextProgressCheckMs = now + PROGRESS_CHECK_INTERVAL_MS;

        try {
            while (true) {
                long t = System.currentTimeMillis();
                if (t >= deadline) {
                    int remaining = safeAirCount(box, lastRemaining);
                    return new Failed(wentActive ? "timeout" : "never_started",
                            wentActive
                                    ? "deadline elapsed; " + remaining + " of " + volume + " cells still air"
                                    : "start window elapsed without builderProcess going active",
                            box, volume, remaining, blockId);
                }
                if (!wentActive && t >= startDeadline) {
                    int remaining = safeAirCount(box, lastRemaining);
                    return new Failed("never_started",
                            "start window elapsed without builderProcess going active",
                            box, volume, remaining, blockId);
                }
                // Cap poll wait at the next progress-check tick once active so
                // we sample air-count even if no Baritone TickSignals arrive.
                long bound = wentActive ? Math.min(deadline, nextProgressCheckMs)
                                        : Math.min(deadline, startDeadline);
                long wait = Math.max(0L, bound - t);
                Signal sig = queue.poll(wait, TimeUnit.MILLISECONDS);

                // Progress watchdog: only meaningful once the builder has gone
                // active. Sample air-count at the configured cadence; if the
                // count strictly decreases, reset the stall timer.
                if (wentActive && System.currentTimeMillis() >= nextProgressCheckMs) {
                    int current = safeAirCount(box, lastRemaining);
                    long tNow = System.currentTimeMillis();
                    if (current < lastRemaining) {
                        lastRemaining = current;
                        lastProgressMs = tNow;
                    }
                    nextProgressCheckMs = tNow + PROGRESS_CHECK_INTERVAL_MS;
                    if (tNow - lastProgressMs >= STUCK_THRESHOLD_MS) {
                        return new Failed("stuck",
                                "no fill progress in " + STUCK_THRESHOLD_MS + "ms; "
                                        + current + " of " + volume + " cells still air",
                                box, volume, current, blockId);
                    }
                }

                if (sig == null) continue;

                if (sig instanceof TickSignal ts) {
                    if (ts.active) {
                        wentActive = true;
                        if (ts.paused) {
                            // Baritone went into its internal paused state — typically a
                            // "Missing materials" stall mid-build. isActive() stays true while
                            // paused (the schematic is still queued; see BuilderProcess.isActive),
                            // so we don't wait for the active→inactive transition. Bail now
                            // with the current air count so the caller can re-stage and retry;
                            // the finally block's cancelEverything will trigger onLostControl
                            // and clear the paused flag for the next call.
                            int remaining = safeAirCount(box, lastRemaining);
                            return new Failed("missing_block",
                                    "Baritone paused mid-fill; " + remaining + " of "
                                            + volume + " cells still air",
                                    box, volume, remaining, blockId);
                        }
                    } else if (wentActive) {
                        int remaining = safeAirCount(box, lastRemaining);
                        if (remaining == 0) {
                            return new Filled("filled",
                                    "fill completed; " + volume + " cells now non-air",
                                    box, volume, 0, blockId);
                        }
                        return new Failed("partial",
                                "builder idled with " + remaining + " of " + volume + " cells still air",
                                box, volume, remaining, blockId);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Failed("internal_error", "interrupted", box, volume, lastRemaining, blockId);
        } finally {
            closed.set(true);
            try {
                ClientThread.supply(() -> {
                    IBaritone bar = BaritoneAPI.getProvider().getPrimaryBaritone();
                    if (bar != null) bar.getPathingBehavior().cancelEverything();
                    return null;
                }).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                HomunculusClient.LOGGER.warn("Fill cleanup cancelEverything threw", e);
            }
            restoreOverrides(snapshot);
        }
    }

    private static boolean hasFillBlockInHotbar(Item fillItem) throws Exception {
        return ClientThread.supply(() -> {
            LocalPlayer p = Minecraft.getInstance().player;
            if (p == null) throw new IllegalStateException("no player");
            Inventory inv = p.getInventory();
            for (int i = 0; i < 9; i++) {
                ItemStack stack = inv.items.get(i);
                if (!stack.isEmpty() && stack.getItem() == fillItem) return true;
            }
            return false;
        }).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private static int safeAirCount(int[] box, int fallback) {
        try {
            return countAir(box);
        } catch (Exception e) {
            HomunculusClient.LOGGER.warn("Fill post-scan threw", e);
            return fallback;
        }
    }

    private static int countAir(int[] box) throws Exception {
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
                        if (bs.isAir()) count++;
                    }
                }
            }
            return count;
        }).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private record Snapshot(
            List<Block> priorBuildIgnoreBlocks,
            Boolean priorBuildInLayers,
            Boolean priorLayerOrder,
            Boolean priorBuildIgnoreExisting) {}

    private static Snapshot applyOverrides() throws Exception {
        return ClientThread.supply(() -> {
            List<Block> priorIgnore = new java.util.ArrayList<>(BaritoneAPI.getSettings().buildIgnoreBlocks.value);
            Boolean priorLayers = BaritoneAPI.getSettings().buildInLayers.value;
            Boolean priorOrder = BaritoneAPI.getSettings().layerOrder.value;
            Boolean priorIgnoreExisting = BaritoneAPI.getSettings().buildIgnoreExisting.value;
            // Empty ignore list: fill doesn't have torches-to-preserve semantics; the schematic
            // is one block type and the only knob we want for "leave alone" is buildIgnoreExisting.
            BaritoneAPI.getSettings().buildIgnoreBlocks.value = new java.util.ArrayList<>();
            BaritoneAPI.getSettings().buildInLayers.value = true;
            BaritoneAPI.getSettings().layerOrder.value = true;
            BaritoneAPI.getSettings().buildIgnoreExisting.value = true;
            return new Snapshot(priorIgnore, priorLayers, priorOrder, priorIgnoreExisting);
        }).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private static void restoreOverrides(Snapshot s) {
        if (s == null) return;
        try {
            ClientThread.supply(() -> {
                BaritoneAPI.getSettings().buildIgnoreBlocks.value = s.priorBuildIgnoreBlocks();
                BaritoneAPI.getSettings().buildInLayers.value = s.priorBuildInLayers();
                BaritoneAPI.getSettings().layerOrder.value = s.priorLayerOrder();
                BaritoneAPI.getSettings().buildIgnoreExisting.value = s.priorBuildIgnoreExisting();
                return null;
            }).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            HomunculusClient.LOGGER.warn("Fill failed to restore Baritone build settings", e);
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
    private record TickSignal(boolean active, boolean paused) implements Signal {}
}
