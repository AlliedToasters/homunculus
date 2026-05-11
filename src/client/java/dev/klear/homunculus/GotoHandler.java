package dev.klear.homunculus;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.event.events.PathEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.GoalBlock;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GotoHandler implements HttpHandler {
    private static final long DEFAULT_TIMEOUT_SECONDS = 60;
    private static final long HARD_CAP_SECONDS = 300;
    private static final long DEFAULT_TOLERANCE = 2;
    private static final long GAME_THREAD_TIMEOUT_MS = 5_000;
    private static final int MAX_BODY_BYTES = 4096;

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
                        "Baritone API not present at runtime", null));
                return;
            }

            if (!Baritone.SESSION_LOCK.tryLock()) {
                respond(exchange, 200, failure(req, "busy",
                        "another /baritone/* call is in flight", null));
                return;
            }

            Outcome outcome;
            try {
                outcome = runGoto(req);
            } finally {
                Baritone.SESSION_LOCK.unlock();
            }

            respond(exchange, 200, outcome.success
                    ? successBody(req, outcome.message, outcome.finalPosition)
                    : failure(req, outcome.reason, outcome.message, outcome.finalPosition));
        } finally {
            exchange.close();
        }
    }

    private static Outcome runGoto(Request req) {
        LinkedBlockingQueue<Signal> queue = new LinkedBlockingQueue<>();
        AtomicBoolean closed = new AtomicBoolean(false);

        AbstractGameEventListener listener = new AbstractGameEventListener() {
            @Override public void onPathEvent(PathEvent event) {
                if (!closed.get()) queue.offer(new PathSignal(event));
            }
            @Override public void onTick(TickEvent event) {
                if (closed.get()) return;
                IBaritone bar = BaritoneAPI.getProvider().getPrimaryBaritone();
                LocalPlayer p = Minecraft.getInstance().player;
                if (bar == null || p == null) return;
                queue.offer(new TickSignal(
                        bar.getCustomGoalProcess().isActive(),
                        bar.getPathingBehavior().isPathing(),
                        p.getX(), p.getY(), p.getZ()));
            }
        };

        double[] initialPos;
        try {
            initialPos = ClientThread.supply(() -> {
                IBaritone bar = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (bar == null) throw new IllegalStateException("no primary Baritone (player not in world?)");
                LocalPlayer p = Minecraft.getInstance().player;
                if (p == null) throw new IllegalStateException("no player");
                bar.getGameEventHandler().registerEventListener(listener);
                bar.getCustomGoalProcess().setGoalAndPath(new GoalBlock(req.x, req.y, req.z));
                return new double[] { p.getX(), p.getY(), p.getZ() };
            }).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            closed.set(true);
            return Outcome.failure("internal_error", "failed to start goto: " + rootMessage(e), null);
        }

        long timeoutMs = Math.min(req.timeoutSeconds, HARD_CAP_SECONDS) * 1000L;
        long deadline = System.currentTimeMillis() + timeoutMs;

        double[] lastPos = initialPos;
        try {
            while (true) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) {
                    return Outcome.failure("timeout", "deadline elapsed without arrival", lastPos);
                }
                Signal sig = queue.poll(wait, TimeUnit.MILLISECONDS);
                if (sig == null) continue;

                if (sig instanceof TickSignal t) {
                    lastPos = new double[] { t.x, t.y, t.z };
                    double dist = manhattan(lastPos, req.x, req.y, req.z);
                    if (dist <= req.arrivalTolerance) {
                        return Outcome.success(
                                "arrived at target within tolerance " + req.arrivalTolerance, lastPos);
                    }
                    if (!t.active && !t.pathing) {
                        return Outcome.failure("stuck",
                                "Baritone idled with " + String.format("%.2f", dist) + " blocks remaining", lastPos);
                    }
                } else if (sig instanceof PathSignal p) {
                    switch (p.event) {
                        case CALC_FAILED -> {
                            return Outcome.failure("unreachable", "Baritone reported PathEvent.CALC_FAILED", lastPos);
                        }
                        case AT_GOAL -> {
                            return Outcome.success("Baritone reported PathEvent.AT_GOAL", lastPos);
                        }
                        case CANCELED -> {
                            return Outcome.failure("canceled", "Baritone reported PathEvent.CANCELED", lastPos);
                        }
                        default -> {}
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.failure("internal_error", "interrupted", lastPos);
        } finally {
            closed.set(true);
            try {
                ClientThread.supply(() -> {
                    IBaritone bar = BaritoneAPI.getProvider().getPrimaryBaritone();
                    if (bar != null) bar.getPathingBehavior().cancelEverything();
                    return null;
                }).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                HomunculusClient.LOGGER.warn("GotoHandler cleanup cancelEverything threw", e);
            }
        }
    }

    private static double manhattan(double[] pos, int tx, int ty, int tz) {
        return Math.abs(pos[0] - tx) + Math.abs(pos[1] - ty) + Math.abs(pos[2] - tz);
    }

    private record Request(int x, int y, int z, long timeoutSeconds, long arrivalTolerance) {}

    private sealed interface Signal {}
    private record TickSignal(boolean active, boolean pathing, double x, double y, double z) implements Signal {}
    private record PathSignal(PathEvent event) implements Signal {}

    private record Outcome(boolean success, String reason, String message, double[] finalPosition) {
        static Outcome success(String message, double[] finalPos) {
            return new Outcome(true, "arrived", message, finalPos);
        }
        static Outcome failure(String reason, String message, double[] finalPos) {
            return new Outcome(false, reason, message, finalPos);
        }
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

        int x = requireInt(m, "x");
        int y = requireInt(m, "y");
        int z = requireInt(m, "z");

        long timeoutSec = DEFAULT_TIMEOUT_SECONDS;
        Object timeoutRaw = m.get("timeout_seconds");
        if (timeoutRaw instanceof Number tn) {
            long t = tn.longValue();
            if (t < 1) throw new IllegalArgumentException("'timeout_seconds' must be >= 1");
            timeoutSec = t;
        } else if (timeoutRaw != null) {
            throw new IllegalArgumentException("'timeout_seconds' must be a number");
        }

        long tolerance = DEFAULT_TOLERANCE;
        Object tolRaw = m.get("arrival_tolerance");
        if (tolRaw instanceof Number tn) {
            long t = tn.longValue();
            if (t < 0) throw new IllegalArgumentException("'arrival_tolerance' must be >= 0");
            tolerance = t;
        } else if (tolRaw != null) {
            throw new IllegalArgumentException("'arrival_tolerance' must be a number");
        }

        return new Request(x, y, z, timeoutSec, tolerance);
    }

    private static int requireInt(Map<?, ?> m, String key) {
        Object raw = m.get(key);
        if (!(raw instanceof Number n)) throw new IllegalArgumentException("'" + key + "' must be an integer");
        if (n.doubleValue() != n.longValue()) throw new IllegalArgumentException("'" + key + "' must be an integer (not a float)");
        long v = n.longValue();
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) throw new IllegalArgumentException("'" + key + "' out of int range");
        return (int) v;
    }

    private static Map<String, Object> successBody(Request req, String message, double[] finalPos) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("reason", "arrived");
        body.put("target", List.of(req.x, req.y, req.z));
        body.put("final_position", positionList(finalPos));
        body.put("message", message);
        return body;
    }

    private static Map<String, Object> failure(Request req, String reason, String message, double[] finalPos) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("reason", reason);
        body.put("target", List.of(req.x, req.y, req.z));
        body.put("final_position", positionList(finalPos));
        body.put("message", message);
        return body;
    }

    private static Object positionList(double[] pos) {
        if (pos == null) return null;
        return List.of(pos[0], pos[1], pos[2]);
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
