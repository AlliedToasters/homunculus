package dev.toast.homunculus;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

/**
 * Heavy tick-indexed sidecar for the obs-ablation frozen capture
 * (neural_interface.md §8e). One JSONL line per client tick holding the raw
 * R3 channels — a block cube and an entity list — plus {@code baritone_state}
 * (§8f). Joined to the per-packet recording ({@link PacketRecorder}) by
 * {@code tick}: same-tick packets share one sidecar row, so the heavy payload
 * is captured once per tick instead of duplicated per packet.
 *
 * <p><b>Raw, not encoded.</b> The cube is captured at radius
 * {@link #CAPTURE_GRID_RADIUS} (exceeds the proposed {@code R_grid=8}) and the
 * entity list at {@link #CAPTURE_ENT_RADIUS} (exceeds {@code R_ent=32}); the
 * encoding (R_grid/R_ent, block-id scheme, F_ent, face_mask) is chosen at
 * projection time. Blocks are air-filtered and stored as
 * {@code [block_id, dx, dy, dz]} offsets from the integer-floored player feet.
 * Entities carry raw kinematics + type only — <b>no threat-salience field</b>
 * (ml.MD §5b). The player is pinned at entity index 0 (player_command targets
 * self).
 *
 * <p><b>Threading.</b> Built on the client thread at {@code END_CLIENT_TICK}
 * (block/entity reads require it); registered <em>after</em>
 * {@link PlayerObsSnapshot} so {@link PlayerObsSnapshot#currentTick()} is
 * already incremented for this tick and the sidecar's {@code tick} matches the
 * obs snapshot the packet recorder stamps. JSON serialization is deferred to
 * the writer thread (the map enqueued is an immutable value snapshot) to keep
 * client-thread cost to the unavoidable block/entity iteration.
 */
public final class TickSidecarRecorder {

    public static final TickSidecarRecorder INSTANCE = new TickSidecarRecorder();

    /** L∞ half-extent of the captured block cube (§8e R_capture_grid). */
    private static final int CAPTURE_GRID_RADIUS = 10;
    /** Radius of the captured entity list (§8e R_capture_ent). */
    private static final double CAPTURE_ENT_RADIUS = 48.0;

    private static final int QUEUE_CAPACITY = 256;
    private static final Map<String, Object> POISON = new LinkedHashMap<>();

    private volatile boolean armed = false;
    private volatile Path currentPath = null;
    private volatile long armedAtMs = 0L;
    private volatile boolean gzip = false;

    private final Object lifecycleLock = new Object();
    private LinkedBlockingQueue<Map<String, Object>> queue;
    private Thread writerThread;

    private final AtomicLong written = new AtomicLong();
    private final AtomicLong droppedQueueFull = new AtomicLong();
    private final AtomicLong droppedNoPlayer = new AtomicLong();
    private final AtomicLong writeFailed = new AtomicLong();

    private TickSidecarRecorder() {}

    /** Hook END_CLIENT_TICK. Must be registered AFTER {@link PlayerObsSnapshot}. */
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> INSTANCE.onTick(client));
    }

    public boolean isArmed() {
        return armed;
    }

    private void onTick(Minecraft client) {
        if (!armed) return;
        LocalPlayer p = client.player;
        ClientLevel level = client.level;
        if (p == null || level == null) {
            droppedNoPlayer.incrementAndGet();
            return;
        }
        long tick = PlayerObsSnapshot.currentTick();
        Map<String, Object> line = buildLine(p, level, tick);
        LinkedBlockingQueue<Map<String, Object>> q = queue;
        if (q == null) return; // race with disarm
        if (!q.offer(line)) {
            droppedQueueFull.incrementAndGet();
        }
    }

    private Map<String, Object> buildLine(LocalPlayer p, ClientLevel level, long tick) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("tick", tick);
        entry.put("captured_at_ms", System.currentTimeMillis());

        int ox = Mth.floor(p.getX());
        int oy = Mth.floor(p.getY());
        int oz = Mth.floor(p.getZ());
        List<Integer> origin = new ArrayList<>(3);
        origin.add(ox);
        origin.add(oy);
        origin.add(oz);
        entry.put("origin", origin);
        entry.put("grid_radius", CAPTURE_GRID_RADIUS);
        entry.put("ent_radius", CAPTURE_ENT_RADIUS);
        addBlockGrid(entry, level, ox, oy, oz);
        entry.put("entity_set", buildEntitySet(p, level));
        entry.put("baritone_state", Baritone.isApiLoaded() ? BaritoneState.snapshot() : null);
        return entry;
    }

    /**
     * Air-filtered cube of side {@code 2*CAPTURE_GRID_RADIUS+1} around the
     * floored player feet, palette-encoded into {@code entry}:
     * <ul>
     *   <li>{@code block_palette} — the distinct block ids in this row, in
     *       first-seen order.</li>
     *   <li>{@code block_grid} — one {@code [palette_idx, dx, dy, dz]} per
     *       non-air cell.</li>
     * </ul>
     * The palette collapses the heavy id-string repetition (one
     * {@code "minecraft:stone"} per row instead of thousands), so both the
     * serialized size and the per-cell work shrink: blocks are singletons, so
     * an {@link IdentityHashMap} dedupes them and {@code getKey().toString()}
     * runs once per distinct block, not once per cell.
     *
     * <p>Cells in unloaded chunks read as air and drop out (chunks within
     * radius 10 of the player are always loaded in practice).
     */
    private static void addBlockGrid(Map<String, Object> entry, ClientLevel level, int ox, int oy, int oz) {
        List<Object> palette = new ArrayList<>();
        Map<Block, Integer> paletteIndex = new IdentityHashMap<>();
        List<Object> blocks = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int r = CAPTURE_GRID_RADIUS;
        for (int dy = -r; dy <= r; dy++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    cursor.set(ox + dx, oy + dy, oz + dz);
                    BlockState bs = level.getBlockState(cursor);
                    if (bs.isAir()) continue;
                    Block block = bs.getBlock();
                    Integer idx = paletteIndex.get(block);
                    if (idx == null) {
                        idx = palette.size();
                        paletteIndex.put(block, idx);
                        palette.add(BuiltInRegistries.BLOCK.getKey(block).toString());
                    }
                    List<Object> rec = new ArrayList<>(4);
                    rec.add(idx);
                    rec.add(dx);
                    rec.add(dy);
                    rec.add(dz);
                    blocks.add(rec);
                }
            }
        }
        entry.put("block_palette", palette);
        entry.put("block_grid", blocks);
    }

    /**
     * Player (index 0, pinned) followed by entities within
     * {@link #CAPTURE_ENT_RADIUS}, nearest-first. Raw kinematics + type; no
     * threat-salience (ml.MD §5b).
     */
    private static List<Object> buildEntitySet(LocalPlayer p, ClientLevel level) {
        List<Object> out = new ArrayList<>();
        out.add(entityRecord(p));
        for (Entity e : Entities.query(p, level, CAPTURE_ENT_RADIUS, e -> true)) {
            out.add(entityRecord(e));
        }
        return out;
    }

    private static Map<String, Object> entityRecord(Entity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runtime_id", e.getId());
        m.put("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
        m.put("x", e.getX());
        m.put("y", e.getY());
        m.put("z", e.getZ());
        Vec3 v = e.getDeltaMovement();
        m.put("vx", v.x);
        m.put("vy", v.y);
        m.put("vz", v.z);
        m.put("yaw", e.getYRot());
        m.put("pitch", e.getXRot());
        m.put("on_ground", e.onGround());
        if (e instanceof LivingEntity le) {
            m.put("health", le.getHealth());
            m.put("max_health", le.getMaxHealth());
        } else {
            m.put("health", null);
            m.put("max_health", null);
        }
        return m;
    }

    /**
     * @param gzip when true, stream-compress the JSONL through a
     *     {@link GZIPOutputStream} (the row data is highly repetitive →
     *     ~5–10×). Opt-in: the default plain writer keeps the channel cheap if
     *     it is ever armed mid-fleet-run; the frozen-capture runner (few agents)
     *     turns it on.
     */
    public Map<String, Object> arm(String pathOrNull, boolean gzip) throws IOException {
        synchronized (lifecycleLock) {
            if (armed) {
                closeStream();
            }
            Path target = resolvePath(pathOrNull, gzip);
            Files.createDirectories(target.getParent());
            BufferedWriter w = gzip ? newGzipWriter(target) : Files.newBufferedWriter(
                    target,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
            this.gzip = gzip;
            LinkedBlockingQueue<Map<String, Object>> q = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
            Thread t = new Thread(() -> drain(w, q), "homunculus-tick-sidecar");
            t.setDaemon(true);
            this.queue = q;
            this.writerThread = t;
            this.currentPath = target;
            this.armedAtMs = System.currentTimeMillis();
            written.set(0);
            droppedQueueFull.set(0);
            droppedNoPlayer.set(0);
            writeFailed.set(0);
            armed = true;
            t.start();
            HomunculusClient.LOGGER.info("[sidecar] armed → {}", target);
            return snapshot();
        }
    }

    public Map<String, Object> disarm() {
        synchronized (lifecycleLock) {
            Path finalPath = currentPath;
            closeStream();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("success", true);
            m.put("armed", false);
            m.put("path", finalPath == null ? null : finalPath.toString());
            m.put("gzip", gzip);
            m.put("armed_at_ms", null);
            m.put("written", written.get());
            m.put("dropped_queue_full", droppedQueueFull.get());
            m.put("dropped_no_player", droppedNoPlayer.get());
            m.put("write_failed", writeFailed.get());
            return m;
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("armed", armed);
        m.put("path", currentPath == null ? null : currentPath.toString());
        m.put("gzip", gzip);
        m.put("armed_at_ms", armedAtMs == 0L ? null : armedAtMs);
        m.put("written", written.get());
        m.put("dropped_queue_full", droppedQueueFull.get());
        m.put("dropped_no_player", droppedNoPlayer.get());
        m.put("write_failed", writeFailed.get());
        return m;
    }

    private static BufferedWriter newGzipWriter(Path target) throws IOException {
        OutputStream os = Files.newOutputStream(
                target,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
        return new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(os), StandardCharsets.UTF_8));
    }

    private Path resolvePath(String pathOrNull, boolean gzip) {
        Path p;
        if (pathOrNull != null && !pathOrNull.isBlank()) {
            p = Paths.get(pathOrNull).toAbsolutePath();
        } else {
            String home = System.getProperty("user.home", ".");
            String name = "sidecar-" + System.currentTimeMillis()
                    + "-" + HomunculusClient.HTTP_PORT + ".jsonl";
            p = Paths.get(home, ".homunculus", "recordings", name).toAbsolutePath();
        }
        if (gzip && !p.toString().endsWith(".gz")) {
            p = Paths.get(p + ".gz");
        }
        return p;
    }

    private void drain(BufferedWriter w, LinkedBlockingQueue<Map<String, Object>> q) {
        try {
            while (true) {
                Map<String, Object> line = q.take();
                if (line == POISON) break;
                try {
                    w.write(Json.write(line));
                    w.write('\n');
                    written.incrementAndGet();
                } catch (IOException e) {
                    writeFailed.incrementAndGet();
                    HomunculusClient.LOGGER.warn("[sidecar] write failed: {}", e.toString());
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
        LinkedBlockingQueue<Map<String, Object>> q = queue;
        Thread t = writerThread;
        Path p = currentPath;
        queue = null;
        writerThread = null;
        currentPath = null;
        armedAtMs = 0L;
        if (q != null) {
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
            HomunculusClient.LOGGER.info("[sidecar] disarmed (written={}, dropped_queue={}, dropped_no_player={}) → {}",
                    written.get(), droppedQueueFull.get(), droppedNoPlayer.get(), p);
        }
    }
}
