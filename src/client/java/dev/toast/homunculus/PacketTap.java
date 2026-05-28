package dev.toast.homunculus;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;

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
 * Phase 0 of the codec experiment (see ml.MD §4a): observe every serverbound
 * packet the client emits. No mutation, no cancel. Proves the {@link
 * net.minecraft.network.Connection#sendPacket} hook works against a live
 * rollout before we start substituting packets in the loop (Phase 1).
 *
 * <p>Records two things per packet:
 * <ol>
 *   <li>An increment on the per-packet-class counter ({@link #countsByClass}).</li>
 *   <li>An entry on a bounded ring buffer ({@link #recent}) with class name
 *       and wall-clock millis. No packet payload is captured — chat / sign /
 *       command argument packets contain user text, and the tap is meant for
 *       distribution-level diagnostics, not transcript replay.</li>
 * </ol>
 *
 * <p>Thread model: writes come from the Netty IO threads (and occasionally the
 * game thread). Reads come from HTTP worker threads. Counters use atomics; the
 * ring buffer is synchronized on itself.
 */
public final class PacketTap {

    private static final int RING_CAPACITY = 256;

    public static final PacketTap INSTANCE = new PacketTap();

    private final ConcurrentMap<String, AtomicLong> countsByClass = new ConcurrentHashMap<>();
    private final AtomicLong total = new AtomicLong();
    private final Deque<RecentEntry> recent = new ArrayDeque<>(RING_CAPACITY);
    private final long startedAtMs = System.currentTimeMillis();

    private PacketTap() {}

    /**
     * Called from the mixin on every outbound packet (filtered to SERVERBOUND
     * upstream). Cheap by design: one atomic increment + one bounded-deque push.
     */
    public void observe(Packet<?> packet) {
        if (packet == null) return;
        String name = packetId(packet);
        countsByClass.computeIfAbsent(name, k -> new AtomicLong()).incrementAndGet();
        total.incrementAndGet();
        long now = System.currentTimeMillis();
        synchronized (recent) {
            if (recent.size() == RING_CAPACITY) recent.removeFirst();
            recent.addLast(new RecentEntry(now, name));
        }
    }

    /** Snapshot of the per-class counters + total + uptime. Safe from any thread. */
    public Map<String, Object> statsSnapshot() {
        Map<String, Long> by = new LinkedHashMap<>();
        // Sort by descending count so the noisy packets surface immediately.
        countsByClass.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .forEach(e -> by.put(e.getKey(), e.getValue().get()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("uptime_ms", System.currentTimeMillis() - startedAtMs);
        out.put("total", total.get());
        out.put("by_class", by);
        return out;
    }

    /** Snapshot of the last {@code n} entries (newest last), capped at {@link #RING_CAPACITY}. */
    public Map<String, Object> recentSnapshot(int n) {
        int cap = Math.max(1, Math.min(n, RING_CAPACITY));
        List<Map<String, Object>> items;
        synchronized (recent) {
            int skip = Math.max(0, recent.size() - cap);
            items = new ArrayList<>(Math.min(cap, recent.size()));
            int i = 0;
            for (RecentEntry e : recent) {
                if (i++ < skip) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("t", e.timestampMs);
                m.put("class", e.packetClass);
                items.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("items", items);
        return out;
    }

    /**
     * Prefer the packet's {@link PacketType#id()} ResourceLocation — that's
     * data ({@code minecraft:move_player_pos}) and survives the fabric-loom
     * jar remap unchanged. Class names go through intermediary at runtime
     * ({@code class_9836}), which is useless for diagnostics. Falls back to
     * the runtime class name only if a packet has no PacketType (shouldn't
     * happen for vanilla packets in 1.21.4 — all of them implement it).
     */
    private static String packetId(Packet<?> packet) {
        try {
            PacketType<?> t = packet.type();
            if (t != null && t.id() != null) {
                return t.id().toString();
            }
        } catch (Throwable ignored) {
            // Defensive: a misbehaving mod packet shouldn't kill the tap.
        }
        return packet.getClass().getName();
    }

    private record RecentEntry(long timestampMs, String packetClass) {}
}
