package dev.toast.homunculus;

import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceLocation;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Recorder for allowlisted serverbound packets and the pre-move player
 * observation that precedes them (ml.MD §4a, step 1 of the test ladder —
 * "static round-trip unit test, recorded pairs reproduce").
 *
 * <p>Lifecycle: {@link #arm(String)} opens a JSONL file and starts a writer
 * thread; {@link #record(Packet, String, long)} is called from the mixin and
 * enqueues a line per allowlisted packet; {@link #disarm()} flushes and closes.
 * No-op when not armed (single volatile read on the network thread).
 *
 * <p>Backpressure: the queue is bounded (1024). On overflow we drop and bump
 * {@link #droppedQueueFull}. Recording is best-effort; the rollout must never
 * stall on disk I/O. If we see drops in practice that's a signal to widen the
 * queue or filter further.
 *
 * <p>Each line carries the wall-clock timestamp, the packet's
 * {@link ResourceLocation} id, the structured fields from
 * {@link PacketFieldExtractor}, and the pre-move
 * {@link PlayerObsSnapshot.Snapshot} captured at END_CLIENT_TICK of the
 * previous tick — the obs the codec needs to express the packet as a delta.
 * Packets fired before the first tick after world-join have no snapshot and
 * are counted as {@link #droppedNoObs} rather than written with a null obs.
 *
 * <p>File path: explicit if passed; otherwise defaults to
 * {@code ${user.home}/.homunculus/recordings/recording-<ts>-<port>.jsonl},
 * where the port disambiguates fleet captures so concurrent agents don't
 * collide.
 */
public final class PacketRecorder {

    public static final PacketRecorder INSTANCE = new PacketRecorder();

    private static final int QUEUE_CAPACITY = 1024;
    private static final String POISON = "\0";

    private volatile boolean armed = false;
    private volatile Path currentPath = null;
    private volatile long armedAtMs = 0L;

    private final Object lifecycleLock = new Object();
    private LinkedBlockingQueue<String> queue;
    private Thread writerThread;
    private BufferedWriter writer;

    private final AtomicLong written = new AtomicLong();
    private final AtomicLong droppedQueueFull = new AtomicLong();
    private final AtomicLong droppedNoObs = new AtomicLong();
    private final AtomicLong writeFailed = new AtomicLong();

    private PacketRecorder() {}

    public boolean isArmed() {
        return armed;
    }

    /**
     * Open a recording at the given path (or a default per-port path if blank).
     * Resets counters. Idempotent on the armed state: a second arm while
     * already armed disarms the previous file first so callers don't have to
     * track the state machine themselves.
     */
    public Map<String, Object> arm(String pathOrNull) throws IOException {
        synchronized (lifecycleLock) {
            if (armed) {
                closeStream();
            }
            Path target = resolvePath(pathOrNull);
            Files.createDirectories(target.getParent());
            // TRUNCATE on arm (not APPEND): arming a recorder means a *fresh*
            // recording. APPEND silently prepended a prior run's packets when a
            // capture dir was reused — and since TICK_COUNTER is cumulative
            // across client life, the stale lines were monotonic and invisible
            // to a tick-sort, only surfacing as a broken packet↔sidecar join.
            // Matches the gzip sidecar (TickSidecarRecorder), which truncates.
            BufferedWriter w = Files.newBufferedWriter(
                    target,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            LinkedBlockingQueue<String> q = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
            Thread t = new Thread(() -> drain(w, q), "homunculus-packet-recorder");
            t.setDaemon(true);
            this.queue = q;
            this.writer = w;
            this.writerThread = t;
            this.currentPath = target;
            this.armedAtMs = System.currentTimeMillis();
            written.set(0);
            droppedQueueFull.set(0);
            droppedNoObs.set(0);
            writeFailed.set(0);
            armed = true;
            t.start();
            HomunculusClient.LOGGER.info("[recorder] armed → {}", target);
            return snapshot();
        }
    }

    /**
     * Flush, close, and stop the writer. Returns a snapshot reflecting the
     * post-close state — armed=false and the final path preserved in the
     * response (closeStream nulls out the live path field, so we capture it
     * before close and inject it into the returned snapshot).
     */
    public Map<String, Object> disarm() {
        synchronized (lifecycleLock) {
            Path finalPath = currentPath;
            closeStream();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("success", true);
            m.put("armed", false);
            m.put("path", finalPath == null ? null : finalPath.toString());
            m.put("armed_at_ms", null);
            m.put("written", written.get());
            m.put("dropped_queue_full", droppedQueueFull.get());
            m.put("dropped_no_obs", droppedNoObs.get());
            m.put("write_failed", writeFailed.get());
            return m;
        }
    }

    /**
     * Called from the mixin per allowlisted outbound packet. Cheap fast-path
     * when disarmed (single volatile read). When armed, builds the JSON line
     * on the calling thread (network thread) — JSON encoding is small and
     * predictable; the disk write happens on the writer thread.
     */
    public void record(Packet<?> packet, String packetId, long tsMs) {
        if (!armed) return;
        PlayerObsSnapshot.Snapshot obs = PlayerObsSnapshot.latest();
        if (obs == null) {
            droppedNoObs.incrementAndGet();
            return;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts_ms", tsMs);
        entry.put("id", packetId);
        entry.put("fields", PacketFieldExtractor.extract(packet));
        entry.put("obs", obs.toRecordJson());
        String line = Json.write(entry);
        LinkedBlockingQueue<String> q = queue;
        if (q == null) return; // race with disarm; drop silently
        if (!q.offer(line)) {
            droppedQueueFull.incrementAndGet();
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("armed", armed);
        m.put("path", currentPath == null ? null : currentPath.toString());
        m.put("armed_at_ms", armedAtMs == 0L ? null : armedAtMs);
        m.put("written", written.get());
        m.put("dropped_queue_full", droppedQueueFull.get());
        m.put("dropped_no_obs", droppedNoObs.get());
        m.put("write_failed", writeFailed.get());
        return m;
    }

    private Path resolvePath(String pathOrNull) {
        if (pathOrNull != null && !pathOrNull.isBlank()) {
            return Paths.get(pathOrNull).toAbsolutePath();
        }
        String home = System.getProperty("user.home", ".");
        String name = "recording-" + System.currentTimeMillis()
                + "-" + HomunculusClient.HTTP_PORT + ".jsonl";
        return Paths.get(home, ".homunculus", "recordings", name).toAbsolutePath();
    }

    private void drain(BufferedWriter w, LinkedBlockingQueue<String> q) {
        try {
            while (true) {
                String line = q.take();
                if (POISON.equals(line)) break;
                try {
                    w.write(line);
                    w.write('\n');
                    written.incrementAndGet();
                } catch (IOException e) {
                    writeFailed.incrementAndGet();
                    HomunculusClient.LOGGER.warn("[recorder] write failed: {}", e.toString());
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            try { w.flush(); } catch (IOException ignored) {}
            try { w.close(); } catch (IOException ignored) {}
        }
    }

    private void closeStream() {
        armed = false;
        LinkedBlockingQueue<String> q = queue;
        Thread t = writerThread;
        Path p = currentPath;
        queue = null;
        writerThread = null;
        writer = null;
        currentPath = null;
        armedAtMs = 0L;
        if (q != null) {
            // Best-effort poison; if the queue is full we still want shutdown,
            // so offer non-blocking and fall back to interrupting the thread.
            if (!q.offer(POISON)) {
                if (t != null) t.interrupt();
            }
        }
        if (t != null) {
            try {
                t.join(TimeUnit.SECONDS.toMillis(2));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        if (p != null) {
            HomunculusClient.LOGGER.info("[recorder] disarmed (written={}, dropped_queue={}, dropped_no_obs={}) → {}",
                    written.get(), droppedQueueFull.get(), droppedNoObs.get(), p);
        }
    }
}
