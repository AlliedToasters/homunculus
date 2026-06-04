package dev.toast.homunculus;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Inbound mirror of {@link PacketTap}: counts the server's corrective-feedback
 * packets (rubber-band position corrections, player velocity overrides) and,
 * when the {@link PacketRecorder} is armed, writes them into the same JSONL
 * stream as the outbound actions so an offline join can attribute a correction
 * to the action that provoked it.
 *
 * <p>Design rationale lives in {@code results/sprintA/FUTURE_rubberband_signal.md}:
 * the server is a free physics oracle; its rejections are a dense, graded,
 * locally-attributable constraint signal (vs the single terminal flying-kick we
 * currently observe post-hoc in the client log).
 *
 * <p>Always-on counting (cheap atomics) gives a live rubber-band rate for
 * sweep observation even when no recording is armed — read it at
 * {@code GET /packets/feedback}. Recording adds the per-correction JSONL line.
 *
 * <p>Thread model identical to {@link PacketTap}: writes from the Netty IO
 * thread (the inbound mixin's {@code channelRead0} HEAD), reads from HTTP
 * worker threads. Counters are atomics; the ring is synchronized on itself.
 */
public final class ServerFeedbackTap {

    private static final int RING_CAPACITY = 128;

    public static final ServerFeedbackTap INSTANCE = new ServerFeedbackTap();

    private final ConcurrentMap<String, AtomicLong> countsById = new ConcurrentHashMap<>();
    private final AtomicLong total = new AtomicLong();
    private final AtomicLong rubberbands = new AtomicLong();      // player_position corrections
    private final AtomicLong motionOverrides = new AtomicLong();  // player set_entity_motion
    private final Deque<Map<String, Object>> recent = new ArrayDeque<>(RING_CAPACITY);
    private final long startedAtMs = System.currentTimeMillis();

    private ServerFeedbackTap() {}

    /**
     * Called from {@link dev.toast.homunculus.mixin.InboundPacketMixin} for
     * corrective packets only (the mixin does the cheap instanceof + local-player
     * filter upstream). Increments counters, pushes the ring, and forwards to
     * the recorder when armed.
     *
     * @param tick the client tick counter at receive time (see
     *             {@link PlayerObsSnapshot#currentTick()})
     * @param tsMs wall-clock receive time, the common join axis with outbound lines
     */
    public void observe(Packet<?> packet, long tick, long tsMs) {
        Map<String, Object> fields = ServerFeedbackExtractor.extract(packet);
        if (fields == null) return; // defensive: mixin already filtered

        String id = packetId(packet);
        countsById.computeIfAbsent(id, k -> new AtomicLong()).incrementAndGet();
        total.incrementAndGet();
        if (packet instanceof ClientboundPlayerPositionPacket) {
            rubberbands.incrementAndGet();
        } else if (packet instanceof ClientboundSetEntityMotionPacket) {
            motionOverrides.incrementAndGet();
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("t", tsMs);
        entry.put("tick", tick);
        entry.put("id", id);
        entry.put("fields", fields);
        synchronized (recent) {
            if (recent.size() == RING_CAPACITY) recent.removeFirst();
            recent.addLast(entry);
        }

        // Reuse the outbound recorder's JSONL + lifecycle so out/in interleave
        // in one time-ordered stream (offline window-join, no second file).
        PacketRecorder.INSTANCE.recordInbound(packet, id, fields, tick, tsMs);
    }

    /** Snapshot of the corrective counters + uptime. Safe from any thread. */
    public Map<String, Object> statsSnapshot() {
        Map<String, Long> by = new LinkedHashMap<>();
        countsById.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .forEach(e -> by.put(e.getKey(), e.getValue().get()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("uptime_ms", System.currentTimeMillis() - startedAtMs);
        out.put("total", total.get());
        out.put("rubberbands", rubberbands.get());
        out.put("motion_overrides", motionOverrides.get());
        out.put("by_id", by);
        return out;
    }

    /** Snapshot of the last {@code n} corrective entries (newest last). */
    public Map<String, Object> recentSnapshot(int n) {
        int cap = Math.max(1, Math.min(n, RING_CAPACITY));
        List<Map<String, Object>> items;
        synchronized (recent) {
            int skip = Math.max(0, recent.size() - cap);
            items = new ArrayList<>(Math.min(cap, recent.size()));
            int i = 0;
            for (Map<String, Object> e : recent) {
                if (i++ < skip) continue;
                items.add(e);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("items", items);
        return out;
    }

    private static String packetId(Packet<?> packet) {
        try {
            PacketType<?> t = packet.type();
            if (t != null && t.id() != null) {
                return t.id().toString();
            }
        } catch (Throwable ignored) {
            // a misbehaving packet shouldn't kill the tap
        }
        return packet.getClass().getName();
    }
}
