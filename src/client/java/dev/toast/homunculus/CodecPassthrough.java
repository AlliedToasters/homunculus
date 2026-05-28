package dev.toast.homunculus;

import net.minecraft.network.protocol.Packet;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live-passthrough validation for the Phase 2 structured codec (ml.MD §4a,
 * test-ladder step 2). When armed, every allowlisted outbound packet has its
 * fields shipped to a Python codec server, round-tripped, and the response
 * checked for drift. The codec lives in Python (single source of truth for
 * train and inference); this class is the bridge that exercises it on live
 * gameplay traffic.
 *
 * <p>Important non-goal: this does NOT substitute the codec's output for the
 * original packet. The seam where bytes hit the wire is unchanged. Step 2 is
 * "is the codec identity in practice at live data rate?"; substituting bytes
 * needs Java-side per-packet reconstruction and is a separate lift. If drift
 * counters stay at zero across a real rollout, that substitution becomes a
 * mechanical follow-up rather than a risk.
 *
 * <p>Lifecycle mirrors {@link PacketRecorder}: arm with an endpoint URL → a
 * bounded queue accepts (packet-id, fields, obs) triples from the network
 * thread → a writer thread drains, POSTs each triple as JSON, updates
 * counters from the response. Disarm flushes the queue (waits up to a small
 * grace period) then stops the thread.
 *
 * <p>Counters:
 * <ul>
 *   <li>{@code attempted} — enqueued (i.e. the observer fired)
 *   <li>{@code ok} — server returned {@code ok:true}
 *   <li>{@code drift} — server returned {@code ok:false} with a structural
 *       mismatch, no exception
 *   <li>{@code transport_errors} — HTTP failed (connect refused, timeout,
 *       non-2xx)
 *   <li>{@code queue_drops} — observer couldn't enqueue (bounded queue full)
 *   <li>{@code no_obs} — observer fired before the first tick snapshot
 * </ul>
 * The relation {@code attempted = ok + drift + transport_errors + queue_drops + no_obs}
 * should hold modulo in-flight requests at snapshot time.
 *
 * <p>Drift logging: on every drift response (up to a small cap per packet
 * type, to avoid log spam if a codec is broken across-the-board) we log the
 * id + the server's error string. The full original fields aren't logged —
 * the recorder is the right tool to capture them; here we want the signal
 * that drift exists.
 */
public final class CodecPassthrough {

    public static final CodecPassthrough INSTANCE = new CodecPassthrough();

    private static final int QUEUE_CAPACITY = 4096;
    private static final long REQUEST_TIMEOUT_MS = 1000;
    private static final long SHUTDOWN_GRACE_MS = 2000;
    private static final int MAX_DRIFT_LOGS_PER_ID = 5;
    private static final String POISON_ID = "\0";

    private volatile boolean armed = false;
    private volatile String endpoint = null;
    private volatile long armedAtMs = 0L;

    private final Object lifecycleLock = new Object();
    private LinkedBlockingQueue<Task> queue;
    private Thread workerThread;
    private HttpClient http;

    private final AtomicLong attempted = new AtomicLong();
    private final AtomicLong ok = new AtomicLong();
    private final AtomicLong drift = new AtomicLong();
    private final AtomicLong transportErrors = new AtomicLong();
    private final AtomicLong queueDrops = new AtomicLong();
    private final AtomicLong noObs = new AtomicLong();

    // Per-packet-type drift log counters — capped so a broken codec doesn't
    // flood logs. Created lazily because the set of ids is small.
    private final Map<String, AtomicLong> driftLogCounts = new java.util.concurrent.ConcurrentHashMap<>();

    private CodecPassthrough() {}

    public boolean isArmed() {
        return armed;
    }

    /**
     * Open a passthrough session pointed at the given codec endpoint. Resets
     * counters. Idempotent against double-arm: a second arm closes the prior
     * worker first.
     */
    public Map<String, Object> arm(String endpointUrl) {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            throw new IllegalArgumentException("endpoint must be a non-empty URL");
        }
        synchronized (lifecycleLock) {
            if (armed) {
                shutdownWorker();
            }
            LinkedBlockingQueue<Task> q = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                    .build();
            Thread t = new Thread(() -> drain(q, client), "homunculus-codec-passthrough");
            t.setDaemon(true);
            this.queue = q;
            this.http = client;
            this.workerThread = t;
            this.endpoint = endpointUrl;
            this.armedAtMs = System.currentTimeMillis();
            attempted.set(0);
            ok.set(0);
            drift.set(0);
            transportErrors.set(0);
            queueDrops.set(0);
            noObs.set(0);
            driftLogCounts.clear();
            armed = true;
            t.start();
            HomunculusClient.LOGGER.info("[codec-passthrough] armed → {}", endpointUrl);
            return snapshot();
        }
    }

    public Map<String, Object> disarm() {
        synchronized (lifecycleLock) {
            String finalEndpoint = endpoint;
            shutdownWorker();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("success", true);
            m.put("armed", false);
            m.put("endpoint", finalEndpoint);
            m.put("armed_at_ms", null);
            m.put("attempted", attempted.get());
            m.put("ok", ok.get());
            m.put("drift", drift.get());
            m.put("transport_errors", transportErrors.get());
            m.put("queue_drops", queueDrops.get());
            m.put("no_obs", noObs.get());
            return m;
        }
    }

    /**
     * Called from the mixin per allowlisted outbound packet. Cheap fast-path
     * when disarmed. When armed, builds the request body on the calling thread
     * (the network thread — JSON encoding is small and predictable) and
     * enqueues for the worker to POST.
     */
    public void observe(Packet<?> packet, String packetId, long tsMs) {
        if (!armed) return;
        PlayerObsSnapshot.Snapshot obs = PlayerObsSnapshot.latest();
        if (obs == null) {
            noObs.incrementAndGet();
            return;
        }
        attempted.incrementAndGet();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", packetId);
        body.put("fields", PacketFieldExtractor.extract(packet));
        body.put("obs", obs.toJson());
        body.put("ts_ms", tsMs);
        String json = Json.write(body);
        LinkedBlockingQueue<Task> q = queue;
        if (q == null) return; // race with disarm
        if (!q.offer(new Task(packetId, json))) {
            queueDrops.incrementAndGet();
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("armed", armed);
        m.put("endpoint", endpoint);
        m.put("armed_at_ms", armedAtMs == 0L ? null : armedAtMs);
        m.put("attempted", attempted.get());
        m.put("ok", ok.get());
        m.put("drift", drift.get());
        m.put("transport_errors", transportErrors.get());
        m.put("queue_drops", queueDrops.get());
        m.put("no_obs", noObs.get());
        return m;
    }

    private void drain(LinkedBlockingQueue<Task> q, HttpClient client) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (Throwable t) {
            HomunculusClient.LOGGER.warn("[codec-passthrough] bad endpoint, draining will no-op: {}", t.toString());
            return;
        }
        HttpRequest.Builder template = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                .header("Content-Type", "application/json");
        try {
            while (true) {
                Task task = q.take();
                if (POISON_ID.equals(task.packetId)) break;
                send(client, template, task);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void send(HttpClient client, HttpRequest.Builder template, Task task) {
        try {
            HttpRequest req = template.copy()
                    .POST(HttpRequest.BodyPublishers.ofString(task.body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                transportErrors.incrementAndGet();
                logBounded(task.packetId, "non-2xx " + resp.statusCode() + ": " + truncate(resp.body(), 120));
                return;
            }
            Object parsed;
            try {
                parsed = Json.parse(resp.body());
            } catch (Throwable t) {
                transportErrors.incrementAndGet();
                logBounded(task.packetId, "unparseable response: " + t);
                return;
            }
            if (!(parsed instanceof Map<?, ?> m)) {
                transportErrors.incrementAndGet();
                logBounded(task.packetId, "non-object response");
                return;
            }
            Object okFlag = m.get("ok");
            if (Boolean.TRUE.equals(okFlag)) {
                ok.incrementAndGet();
                return;
            }
            // Anything else is structured drift. Includes encode/decode
            // exceptions (server reports {ok:false, error:"encode ..."}) and
            // fields_close mismatches — both are codec-correctness signals.
            drift.incrementAndGet();
            Object err = m.get("error");
            logBounded(task.packetId, "drift: " + (err == null ? "<no error message>" : err.toString()));
        } catch (IOException | InterruptedException e) {
            transportErrors.incrementAndGet();
            logBounded(task.packetId, "transport: " + e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        } catch (Throwable t) {
            transportErrors.incrementAndGet();
            logBounded(task.packetId, "unexpected: " + t);
        }
    }

    private void logBounded(String packetId, String message) {
        AtomicLong counter = driftLogCounts.computeIfAbsent(packetId, k -> new AtomicLong());
        long n = counter.incrementAndGet();
        if (n <= MAX_DRIFT_LOGS_PER_ID) {
            HomunculusClient.LOGGER.warn("[codec-passthrough] {} {}", packetId, message);
            if (n == MAX_DRIFT_LOGS_PER_ID) {
                HomunculusClient.LOGGER.warn("[codec-passthrough] {} log cap reached; further {} drifts will be silent", packetId, packetId);
            }
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return "<null>";
        if (s.length() <= n) return s;
        return s.substring(0, n) + "...";
    }

    private void shutdownWorker() {
        armed = false;
        LinkedBlockingQueue<Task> q = queue;
        Thread t = workerThread;
        String e = endpoint;
        queue = null;
        workerThread = null;
        http = null;
        endpoint = null;
        armedAtMs = 0L;
        if (q != null) {
            if (!q.offer(new Task(POISON_ID, ""))) {
                if (t != null) t.interrupt();
            }
        }
        if (t != null) {
            try {
                t.join(SHUTDOWN_GRACE_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        if (e != null) {
            HomunculusClient.LOGGER.info(
                    "[codec-passthrough] disarmed (attempted={}, ok={}, drift={}, transport={}, drops={}, no_obs={}) → {}",
                    attempted.get(), ok.get(), drift.get(),
                    transportErrors.get(), queueDrops.get(), noObs.get(), e);
        }
    }

    private record Task(String packetId, String body) {}
}
