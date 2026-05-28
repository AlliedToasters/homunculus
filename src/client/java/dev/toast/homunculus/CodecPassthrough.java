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
    // Optional: when set, each packet's obs is also POSTed to the inference
    // server alongside the codec round-trip. The response's predicted_type is
    // compared to the actual packet id and counted in predictedCorrect /
    // predictedTotal. Does not affect the wire — purely observational.
    private volatile String inferenceUrl = null;
    // When true, the codec round-trip happens SYNCHRONOUSLY on the network
    // thread (not via the async worker) and the codec's decoded fields are
    // reconstructed into a packet that goes on the wire instead of the
    // original. Smoke-test scope (ml.MD §4a step 2, interpretation A): only
    // ServerboundMovePlayerPacket is reconstructable today. For types without
    // a reconstructor we fall back to pass-through.
    private volatile boolean substitute = false;
    private volatile long armedAtMs = 0L;

    // Shared sync HTTP client for substitute=true path (separate from the
    // async worker's client to avoid threading questions).
    private final HttpClient syncHttp = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
            .build();

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
    // Inference prediction counters (only meaningful when inferenceUrl is set).
    private final AtomicLong predictedTotal = new AtomicLong();
    private final AtomicLong predictedCorrect = new AtomicLong();
    // Substitution counters (only meaningful when substitute is true).
    private final AtomicLong substituted = new AtomicLong();
    private final AtomicLong substituteFallbacks = new AtomicLong();
    private final AtomicLong substituteErrors = new AtomicLong();

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
    /**
     * @param endpointUrl   codec round-trip URL (required)
     * @param inferenceUrl  optional neural inference URL; if non-blank, each
     *                      packet's obs is also sent to this endpoint and the
     *                      predicted_type is compared to the actual packet id.
     */
    public Map<String, Object> arm(String endpointUrl, String inferenceUrl, boolean substitute) {
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
            this.inferenceUrl = (inferenceUrl != null && !inferenceUrl.isBlank()) ? inferenceUrl : null;
            this.substitute = substitute;
            this.armedAtMs = System.currentTimeMillis();
            attempted.set(0);
            ok.set(0);
            drift.set(0);
            transportErrors.set(0);
            queueDrops.set(0);
            noObs.set(0);
            predictedTotal.set(0);
            predictedCorrect.set(0);
            substituted.set(0);
            substituteFallbacks.set(0);
            substituteErrors.set(0);
            driftLogCounts.clear();
            armed = true;
            t.start();
            HomunculusClient.LOGGER.info("[codec-passthrough] armed → {} (inference: {}, substitute: {})",
                    endpointUrl,
                    this.inferenceUrl != null ? this.inferenceUrl : "none",
                    this.substitute);
            return snapshot();
        }
    }

    public Map<String, Object> arm(String endpointUrl, String inferenceUrl) {
        return arm(endpointUrl, inferenceUrl, false);
    }

    public Map<String, Object> arm(String endpointUrl) {
        return arm(endpointUrl, null, false);
    }

    public boolean isSubstituteMode() {
        return armed && substitute;
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
            m.put("predicted_total", predictedTotal.get());
            m.put("predicted_correct", predictedCorrect.get());
            m.put("substituted", substituted.get());
            m.put("substitute_fallbacks", substituteFallbacks.get());
            m.put("substitute_errors", substituteErrors.get());
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

    /**
     * Synchronous substitute path. Called from the mixin BEFORE the original
     * packet is sent, on the network thread. Returns the clone to substitute
     * (caller cancels the original and sends the clone), or {@code null} to
     * pass the original through unchanged.
     *
     * <p>Increments counters:
     * <ul>
     *   <li>{@link #substituted} on successful substitution
     *   <li>{@link #substituteFallbacks} when the packet type has no
     *       reconstructor (deliberate, not an error)
     *   <li>{@link #substituteErrors} on codec round-trip failure or
     *       transport error (logged at debug; falls back to original)
     * </ul>
     */
    public Packet<?> trySubstitute(Packet<?> packet, String packetId, long tsMs) {
        if (!armed || !substitute) return null;
        if (!PacketReconstructor.canReconstruct(packetId)) {
            substituteFallbacks.incrementAndGet();
            return null;
        }
        PlayerObsSnapshot.Snapshot obs = PlayerObsSnapshot.latest();
        if (obs == null) {
            noObs.incrementAndGet();
            return null;
        }
        attempted.incrementAndGet();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", packetId);
        body.put("fields", PacketFieldExtractor.extract(packet));
        body.put("obs", obs.toJson());
        body.put("ts_ms", tsMs);
        String json = Json.write(body);

        try {
            URI uri = URI.create(endpoint);
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = syncHttp.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                substituteErrors.incrementAndGet();
                logBounded(packetId, "substitute non-2xx " + resp.statusCode());
                return null;
            }
            Object parsed = Json.parse(resp.body());
            if (!(parsed instanceof Map<?, ?> m)) {
                substituteErrors.incrementAndGet();
                return null;
            }
            if (!Boolean.TRUE.equals(m.get("ok"))) {
                drift.incrementAndGet();
                Object err = m.get("error");
                logBounded(packetId, "substitute drift: " + err);
                return null;
            }
            Object decodedRaw = m.get("decoded");
            if (!(decodedRaw instanceof Map<?, ?>)) {
                substituteErrors.incrementAndGet();
                logBounded(packetId, "substitute no decoded fields in response");
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> decoded = (Map<String, Object>) decodedRaw;
            Packet<?> clone = PacketReconstructor.build(packetId, decoded);
            if (clone == null) {
                substituteErrors.incrementAndGet();
                logBounded(packetId, "substitute reconstructor returned null");
                return null;
            }
            ok.incrementAndGet();
            substituted.incrementAndGet();
            return clone;
        } catch (IOException | InterruptedException e) {
            substituteErrors.incrementAndGet();
            transportErrors.incrementAndGet();
            logBounded(packetId, "substitute transport: " + e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        } catch (Throwable t) {
            substituteErrors.incrementAndGet();
            logBounded(packetId, "substitute unexpected: " + t);
            return null;
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
        m.put("predicted_total", predictedTotal.get());
        m.put("predicted_correct", predictedCorrect.get());
        if (predictedTotal.get() > 0) {
            m.put("predicted_accuracy", (double) predictedCorrect.get() / predictedTotal.get());
        }
        m.put("substitute_mode", substitute);
        m.put("substituted", substituted.get());
        m.put("substitute_fallbacks", substituteFallbacks.get());
        m.put("substitute_errors", substituteErrors.get());
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
        String infUrl = inferenceUrl; // snapshot at drain-start; null if not set
        URI inferUri = null;
        if (infUrl != null) {
            try {
                inferUri = URI.create(infUrl);
            } catch (Throwable t) {
                HomunculusClient.LOGGER.warn("[codec-passthrough] bad inference_url, inference disabled: {}", t.toString());
            }
        }
        HttpRequest.Builder codecTemplate = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                .header("Content-Type", "application/json");
        HttpRequest.Builder inferTemplate = inferUri == null ? null
                : HttpRequest.newBuilder(inferUri)
                    .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                    .header("Content-Type", "application/json");
        try {
            while (true) {
                Task task = q.take();
                if (POISON_ID.equals(task.packetId)) break;
                send(client, codecTemplate, task);
                if (inferTemplate != null) {
                    infer(client, inferTemplate, task);
                }
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

    /**
     * POST obs to the inference server and compare the predicted_type to the
     * actual packet id. Increments predictedTotal / predictedCorrect.
     * Fire-and-count: errors are logged but do not increment drift counters
     * (inference is separate from codec correctness).
     */
    private void infer(HttpClient client, HttpRequest.Builder template, Task task) {
        // The inference server only needs obs, not fields. Re-use task.body
        // which already has "obs" embedded; send the whole body and let the
        // server ignore extra keys (it reads only "obs").
        try {
            // Build a minimal {"obs": {...}} body from the task body's obs field.
            // task.body is {"id":..., "fields":..., "obs":..., "ts_ms":...}.
            // The inference server accepts that shape too (it reads "obs" key).
            HttpRequest req = template.copy()
                    .POST(HttpRequest.BodyPublishers.ofString(task.body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                HomunculusClient.LOGGER.debug("[codec-passthrough/infer] non-2xx {} for {}", resp.statusCode(), task.packetId);
                return;
            }
            Object parsed;
            try {
                parsed = Json.parse(resp.body());
            } catch (Throwable t) {
                return;
            }
            if (!(parsed instanceof Map<?, ?> m)) return;
            Object predicted = m.get("predicted_type");
            if (!(predicted instanceof String predictedStr)) return;
            predictedTotal.incrementAndGet();
            boolean correct = task.packetId.equals(predictedStr);
            if (correct) predictedCorrect.incrementAndGet();
            // Log mismatches at DEBUG — at inference these will be ~35% wrong
            // and we don't want info-level spam.
            Object conf = ((Map<?, ?>) m).get("confidence");
            Object latMs = ((Map<?, ?>) m).get("latency_ms");
            HomunculusClient.LOGGER.debug(
                    "[codec-passthrough/infer] actual={} predicted={} conf={} latency_ms={}{}",
                    task.packetId, predictedStr,
                    conf != null ? conf : "?",
                    latMs != null ? latMs : "?",
                    correct ? "" : " WRONG");
        } catch (IOException | InterruptedException e) {
            HomunculusClient.LOGGER.debug("[codec-passthrough/infer] transport error: {}", e.toString());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        } catch (Throwable t) {
            HomunculusClient.LOGGER.debug("[codec-passthrough/infer] unexpected: {}", t.toString());
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
            long ptotal = predictedTotal.get();
            String inferStats = ptotal > 0
                    ? String.format(" infer=%d/%d(%.2f)", predictedCorrect.get(), ptotal,
                            (double) predictedCorrect.get() / ptotal)
                    : "";
            HomunculusClient.LOGGER.info(
                    "[codec-passthrough] disarmed (attempted={}, ok={}, drift={}, transport={}, drops={}, no_obs={}{}) → {}",
                    attempted.get(), ok.get(), drift.get(),
                    transportErrors.get(), queueDrops.get(), noObs.get(), inferStats, e);
        }
    }

    private record Task(String packetId, String body) {}
}
