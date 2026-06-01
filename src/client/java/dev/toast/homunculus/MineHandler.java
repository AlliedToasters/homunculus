package dev.toast.homunculus;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.event.events.PathEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.utils.BlockOptionalMeta;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MineHandler implements HttpHandler {
    private static final long DEFAULT_TIMEOUT_SECONDS = 45;
    private static final long DEFAULT_START_WINDOW_SECONDS = 15;
    private static final long HARD_CAP_SECONDS = 300;
    private static final long GAME_THREAD_TIMEOUT_MS = 5_000;
    private static final long BOM_PREWARM_TIMEOUT_MS = 30_000;
    private static final int MAX_BODY_BYTES = 4096;

    // No-progress watchdog. While the mine process is active, sample every
    // PROGRESS_CHECK_INTERVAL_MS: progress = inventory match count increased
    // OR the player moved >= MOVE_EPS blocks (net) from the last progress
    // position. If neither happens for MINE_STUCK_THRESHOLD_MS, the target is
    // present-but-unreachable: Baritone's MineProcess re-paths forever (routine
    // PathEvent.CANCELED, never CALC_FAILED) and would otherwise burn the full
    // 45s deadline. Position-movement distinguishes "walking to a far tree"
    // (keep going) from "oscillating at an unreachable target" (bail).
    private static final long PROGRESS_CHECK_INTERVAL_MS = 2_000;
    private static final long MINE_STUCK_THRESHOLD_MS = readStuckThresholdMs();
    // Net displacement (squared, in blocks) that counts as "still making
    // travel progress". ~3 blocks: a genuine walk clears this every 2s sample;
    // oscillation in a tight notch never does.
    private static final double MOVE_EPS_SQ = 9.0;

    private static long readStuckThresholdMs() {
        String raw = System.getenv("HOMUNCULUS_MINE_STUCK_THRESHOLD_MS");
        if (raw == null || raw.isBlank()) return 12_000L;
        try {
            long v = Long.parseLong(raw.trim());
            return v < 1_000L ? 1_000L : v;
        } catch (NumberFormatException e) {
            return 12_000L;
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, Map.of("error", "method not allowed"));
                return;
            }

            Request req;
            try {
                req = parseBody(exchange);
            } catch (IllegalArgumentException e) {
                respond(exchange, 400, Map.of("error", "bad request: " + e.getMessage()));
                return;
            }

            if (!Baritone.isApiLoaded()) {
                respond(exchange, 200, failure(req, "baritone_not_loaded",
                        "Baritone API not present at runtime"));
                return;
            }

            ResourceLocation id = ResourceLocation.tryParse(req.block);
            if (id == null) {
                respond(exchange, 200, failure(req, "unknown_block",
                        "could not parse block id '" + req.block + "'"));
                return;
            }
            if (!BuiltInRegistries.BLOCK.containsKey(id)) {
                respond(exchange, 200, failure(req, "unknown_block",
                        "no block registered for id '" + id + "'"));
                return;
            }
            Block block = BuiltInRegistries.BLOCK.getValue(id);

            if (!Baritone.SESSION_LOCK.tryLock()) {
                respond(exchange, 200, failure(req, "busy",
                        "another /baritone/* call is in flight"));
                return;
            }

            Outcome outcome;
            try {
                outcome = runMine(req, block);
            } finally {
                Baritone.SESSION_LOCK.unlock();
            }

            respond(exchange, 200, outcome.success
                    ? successBody(req, outcome.reason, outcome.message)
                    : failure(req, outcome.reason, outcome.message));
        } finally {
            exchange.close();
        }
    }

    private static Outcome runMine(Request req, Block block) {
        // Off-render-thread BOM prewarm. BlockOptionalMeta.<init> calls the static synchronized
        // drops() which joins on a registry future that deadlocks if invoked from the render
        // thread on a MP client. By constructing the BOM here (on the HTTP worker thread) the
        // render thread stays free to drive the registry lookup, and the populated drops cache
        // makes future BOM constructions for this block cheap on any thread.
        BlockOptionalMeta bom;
        try {
            bom = CompletableFuture
                    .supplyAsync(() -> new BlockOptionalMeta(block))
                    .get(BOM_PREWARM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return Outcome.failure("internal_error",
                    "BOM prewarm timed out after " + BOM_PREWARM_TIMEOUT_MS
                            + "ms (off-thread drops() did not resolve)");
        } catch (Exception e) {
            return Outcome.failure("internal_error",
                    "BOM prewarm threw: " + rootMessage(e));
        }

        // Inventory pre-check: Baritone's mine process treats `count` as a cumulative target
        // and self-deactivates immediately if it's already satisfied. Detect that here so
        // callers see a clear `already_satisfied` instead of waiting out our start window.
        int preCount;
        try {
            preCount = countMatches(bom);
        } catch (Exception e) {
            return Outcome.failure("internal_error",
                    "inventory pre-check threw: " + rootMessage(e));
        }
        if (preCount >= req.count) {
            return Outcome.success("already_satisfied",
                    "inventory already has " + preCount + " matching " + req.block
                            + " (target " + req.count + ")");
        }

        LinkedBlockingQueue<Signal> queue = new LinkedBlockingQueue<>();
        AtomicBoolean closed = new AtomicBoolean(false);

        AbstractGameEventListener listener = new AbstractGameEventListener() {
            @Override public void onPathEvent(PathEvent event) {
                if (!closed.get()) queue.offer(new PathSignal(event));
            }
            @Override public void onTick(TickEvent event) {
                if (closed.get()) return;
                IBaritone bar = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (bar == null) return;
                queue.offer(new TickSignal(bar.getMineProcess().isActive()));
            }
        };

        try {
            ClientThread.supply(() -> {
                IBaritone bar = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (bar == null) throw new IllegalStateException("no primary Baritone (player not in world?)");
                bar.getGameEventHandler().registerEventListener(listener);
                bar.getMineProcess().mine(req.count, bom);
                return null;
            }).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            closed.set(true);
            return Outcome.failure("internal_error", "failed to start mine: " + rootMessage(e));
        }

        long now = System.currentTimeMillis();
        long startDeadline = now + DEFAULT_START_WINDOW_SECONDS * 1000L;
        long timeoutMs = Math.min(req.timeoutSeconds, HARD_CAP_SECONDS) * 1000L;
        long deadline = now + timeoutMs;
        boolean wentActive = false;
        // No-progress watchdog state. Baselined when the mine first goes active.
        int lastInvCount = preCount;
        BlockPos lastProgressPos = null;
        long lastProgressMs = now;
        long nextProgressCheckMs = now + PROGRESS_CHECK_INTERVAL_MS;

        try {
            while (true) {
                long t = System.currentTimeMillis();
                if (t >= deadline) {
                    return wentActive
                            ? Outcome.failure("timeout", "deadline elapsed; mine still running")
                            : Outcome.failure("never_started",
                                    "start window elapsed without mineProcess going active");
                }
                if (!wentActive && t >= startDeadline) {
                    return Outcome.failure("never_started",
                            "start window elapsed without mineProcess going active");
                }
                long bound = wentActive ? Math.min(deadline, nextProgressCheckMs)
                                        : Math.min(deadline, startDeadline);
                long wait = Math.max(0L, bound - t);
                Signal sig = queue.poll(wait, TimeUnit.MILLISECONDS);

                // No-progress sampling. Runs on the worker thread between polls
                // (countMatches/playerBlockPos hop to the client thread). A
                // transient scan exception is treated as progress so we never
                // bail on a flaky read.
                if (wentActive && System.currentTimeMillis() >= nextProgressCheckMs) {
                    long tNow = System.currentTimeMillis();
                    boolean progressed = false;
                    try {
                        int curInv = countMatches(bom);
                        if (curInv > lastInvCount) {
                            lastInvCount = curInv;
                            progressed = true;
                        }
                        BlockPos curPos = playerBlockPos();
                        if (lastProgressPos == null) {
                            lastProgressPos = curPos;
                            progressed = true;
                        } else if (distSq(curPos, lastProgressPos) >= MOVE_EPS_SQ) {
                            lastProgressPos = curPos;
                            progressed = true;
                        }
                    } catch (Exception e) {
                        progressed = true;
                    }
                    if (progressed) lastProgressMs = tNow;
                    nextProgressCheckMs = tNow + PROGRESS_CHECK_INTERVAL_MS;
                    if (tNow - lastProgressMs >= MINE_STUCK_THRESHOLD_MS) {
                        return Outcome.failure("no_progress",
                                "no inventory gain + player stationary for " + MINE_STUCK_THRESHOLD_MS
                                        + "ms — target likely unreachable (have " + lastInvCount
                                        + " of " + req.count + ")");
                    }
                }

                if (sig == null) continue;

                if (sig instanceof TickSignal ts) {
                    if (ts.active) {
                        if (!wentActive) {
                            // Baseline the watchdog at the moment mining starts so
                            // the start-window wait doesn't count against progress.
                            wentActive = true;
                            long tNow = System.currentTimeMillis();
                            lastProgressMs = tNow;
                            nextProgressCheckMs = tNow + PROGRESS_CHECK_INTERVAL_MS;
                            lastProgressPos = null;
                        }
                    } else if (wentActive) {
                        // mineProcess deactivated post-start. Inventory check decides whether
                        // the target was actually hit (success) or the process was canceled
                        // mid-mine (e.g. external /baritone/stop, no candidates left).
                        int postCount;
                        try {
                            postCount = countMatches(bom);
                        } catch (Exception e) {
                            return Outcome.failure("internal_error",
                                    "inventory post-check threw: " + rootMessage(e));
                        }
                        if (postCount >= req.count) {
                            return Outcome.success("have_target",
                                    "mine completed; inventory has " + postCount
                                            + " of " + req.block);
                        }
                        return Outcome.failure("interrupted",
                                "mine deactivated before target reached: have " + postCount
                                        + " of " + req.count);
                    }
                } else if (sig instanceof PathSignal p) {
                    // Baritone fires PathEvent.CANCELED on routine inter-segment path
                    // transitions during a mine (path A finishes, planner cancels and replans
                    // for the next tree). Only treat CALC_FAILED as terminal — the mine
                    // process itself signals completion via isActive() flipping false.
                    if (p.event == PathEvent.CALC_FAILED) {
                        return Outcome.failure("unreachable",
                                "Baritone reported PathEvent.CALC_FAILED");
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.failure("internal_error", "interrupted");
        } finally {
            closed.set(true);
            try {
                ClientThread.supply(() -> {
                    IBaritone bar = BaritoneAPI.getProvider().getPrimaryBaritone();
                    if (bar != null) bar.getPathingBehavior().cancelEverything();
                    return null;
                }).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                HomunculusClient.LOGGER.warn("MineHandler cleanup cancelEverything threw", e);
            }
        }
    }

    private record Request(String block, int count, long timeoutSeconds) {}

    private sealed interface Signal {}
    private record TickSignal(boolean active) implements Signal {}
    private record PathSignal(PathEvent event) implements Signal {}

    private record Outcome(boolean success, String reason, String message) {
        static Outcome success(String reason, String message) {
            return new Outcome(true, reason, message);
        }
        static Outcome failure(String reason, String message) {
            return new Outcome(false, reason, message);
        }
    }

    private static BlockPos playerBlockPos() throws Exception {
        return ClientThread.supply(() -> {
            LocalPlayer p = Minecraft.getInstance().player;
            if (p == null) throw new IllegalStateException("no player");
            return p.blockPosition();
        }).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private static double distSq(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static int countMatches(BlockOptionalMeta bom) throws Exception {
        return ClientThread.supply(() -> {
            LocalPlayer p = Minecraft.getInstance().player;
            if (p == null) throw new IllegalStateException("no player");
            Inventory inv = p.getInventory();
            int total = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty() && bom.matches(stack)) total += stack.getCount();
            }
            return total;
        }).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private static Request parseBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) throw new IllegalArgumentException("body too large");
        String body = new String(bytes, StandardCharsets.UTF_8).trim();
        if (body.isEmpty()) throw new IllegalArgumentException("empty body");
        Object parsed;
        try {
            parsed = Json.parse(body);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid JSON (" + e.getMessage() + ")");
        }
        if (!(parsed instanceof Map<?, ?> m)) throw new IllegalArgumentException("expected JSON object");

        Object blockRaw = m.get("block");
        if (!(blockRaw instanceof String bs) || bs.isBlank()) {
            throw new IllegalArgumentException("'block' must be a non-empty string");
        }
        String block = bs.contains(":") ? bs : ("minecraft:" + bs);

        Object countRaw = m.get("count");
        if (!(countRaw instanceof Number n)) throw new IllegalArgumentException("'count' must be an integer");
        if (n.doubleValue() != n.longValue()) throw new IllegalArgumentException("'count' must be an integer (not a float)");
        long countL = n.longValue();
        if (countL < 1 || countL > Integer.MAX_VALUE) throw new IllegalArgumentException("'count' must be between 1 and Integer.MAX_VALUE");
        int count = (int) countL;

        long timeoutSec = DEFAULT_TIMEOUT_SECONDS;
        Object timeoutRaw = m.get("timeout_seconds");
        if (timeoutRaw instanceof Number tn) {
            long t = tn.longValue();
            if (t < 1) throw new IllegalArgumentException("'timeout_seconds' must be >= 1");
            timeoutSec = t;
        } else if (timeoutRaw != null) {
            throw new IllegalArgumentException("'timeout_seconds' must be a number");
        }

        return new Request(block, count, timeoutSec);
    }

    private static Map<String, Object> successBody(Request req, String reason, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("reason", reason);
        body.put("block", req.block);
        body.put("target", req.count);
        body.put("message", message);
        return body;
    }

    private static Map<String, Object> failure(Request req, String reason, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("reason", reason);
        body.put("block", req.block);
        body.put("target", req.count);
        body.put("message", message);
        return body;
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t.getCause() != null ? t.getCause() : t;
        String m = c.getMessage();
        return m == null ? c.getClass().getSimpleName() : m;
    }

    private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
