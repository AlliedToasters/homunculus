package dev.klear.homunculus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-process registry of agent-initiated smelts, keyed by furnace {@link BlockPos}.
 *
 * <p>Cook progress is tracked in {@code cumulativeLoadedMs} — the total wall-clock time the entry's
 * chunk has been loaded since ignition — NOT wall-clock-since-start. Vanilla MC pauses cooks while
 * the chunk is unloaded; using cumulative-loaded-time keeps the registry's belief honest. The
 * ticker (see {@link FurnaceTicker}) increments this once per client tick when the chunk is loaded.
 *
 * <p>Reconciliation:
 * <ul>
 *   <li><b>Tick-driven:</b> per-tick, the ticker checks each entry's chunk-loaded state and
 *       whether the block at {@code pos} is still a furnace. Transitions: chunk-unloaded → STALE;
 *       furnace block removed → DESTROYED; cumulative-loaded covers expected_output → READY or
 *       PARTIAL.</li>
 *   <li><b>Menu-driven:</b> {@link Smelter#collectFromFurnace} opens the menu and reads actual slot
 *       contents, overwriting the extrapolated values. Authoritative when run.</li>
 * </ul>
 *
 * <p>Threading: writes happen on the client/render thread (ticker, /smelt registration,
 * /collect_smelt reconciliation). Reads happen on HTTP worker threads. {@code Entry} is an
 * immutable record; the backing map is concurrent.
 */
public final class FurnaceRegistry {
	private FurnaceRegistry() {}

	public enum Status {
		COOKING, READY, PARTIAL, STALE, DESTROYED, EMPTY;
		public String wire() { return name().toLowerCase(); }
	}

	/** A single fuel type loaded into a furnace. v1.2 enforces single-fuel-type per smelt. */
	public record FuelLoaded(ResourceLocation id, int count, int burnTicksEach) {
		public long totalBurnTicks() { return (long) count * burnTicksEach; }
	}

	/**
	 * One registered smelt. Immutable; mutations create a replacement and call {@link #put}.
	 *
	 * <p>{@code outputCountExpected} is the *actual* number of items the loaded fuel can produce
	 * (= min(inputCountInitial, totalFuelBurnTicks / cookTicksPerBatch)). When fuel is short of
	 * input, this is less than what the agent asked for; the entry transitions to
	 * {@link Status#PARTIAL} after that many smelts complete.
	 */
	public record Entry(
			ResourceKey<Level> dimension,
			BlockPos pos,
			ResourceLocation inputItem,
			int inputCountInitial,
			ResourceLocation resultItem,
			int outputCountExpected,
			List<FuelLoaded> fuelLoaded,
			int cookTicksPerBatch,
			long startedAtMs,            // wall-clock ignition time (display only)
			long cumulativeLoadedMs,     // cook clock: only advances while chunk is loaded
			Status status,
			long lastObservedMs,
			int lastKnownCountReady,
			int lastKnownCountRemaining,
			int lastKnownFuelRemainingBurns
	) {
		public Entry with(Status s, long cumulativeLoadedMs, long observedMs,
						   int ready, int remaining, int fuelBurns) {
			return new Entry(dimension, pos, inputItem, inputCountInitial,
					resultItem, outputCountExpected, fuelLoaded,
					cookTicksPerBatch, startedAtMs,
					cumulativeLoadedMs, s, observedMs, ready, remaining, fuelBurns);
		}

		/** Total ms the cook needs to reach expected_output (assuming continuous burn). */
		public long totalCookDurationMs() {
			return (long) outputCountExpected * cookTicksPerBatch * 50L;
		}

		/** ETA in whole seconds from now to {@link Status#READY}; null when stale or terminal. */
		public Long etaSeconds() {
			if (status == Status.STALE || status == Status.DESTROYED) return null;
			if (status == Status.READY || status == Status.PARTIAL || status == Status.EMPTY) return 0L;
			long remaining = Math.max(0L, totalCookDurationMs() - cumulativeLoadedMs);
			return (remaining + 999L) / 1000L;
		}

		/** 0..1 progress over the cook batch; capped at 1.0. Null when stale. */
		public Double cookProgress() {
			if (status == Status.STALE) return null;
			long total = totalCookDurationMs();
			if (total <= 0) return 1.0;
			return Math.min(1.0, cumulativeLoadedMs / (double) total);
		}
	}

	private static final ConcurrentMap<BlockPos, Entry> ENTRIES = new ConcurrentHashMap<>();

	public static void put(Entry e) { ENTRIES.put(e.pos(), e); }
	public static Entry get(BlockPos pos) { return ENTRIES.get(pos); }
	public static List<Entry> all() { return new ArrayList<>(ENTRIES.values()); }
	public static void remove(BlockPos pos) { ENTRIES.remove(pos); }
	public static int size() { return ENTRIES.size(); }
	public static void clear() { ENTRIES.clear(); }

	/**
	 * Extrapolate the live count_ready / count_remaining / fuel_remaining_burns for a non-stale entry
	 * from its current cumulativeLoadedMs. For stale/destroyed/empty entries, returns the
	 * lastKnown* snapshot unchanged.
	 */
	public static Snapshot extrapolate(Entry e) {
		if (e.status() == Status.STALE
				|| e.status() == Status.DESTROYED
				|| e.status() == Status.EMPTY) {
			return new Snapshot(e.lastKnownCountReady(),
					e.lastKnownCountRemaining(),
					e.lastKnownFuelRemainingBurns());
		}
		return computeSnapshot(e, e.cumulativeLoadedMs());
	}

	/** Pure computation: project counts at the given cumulativeLoadedMs. */
	public static Snapshot computeSnapshot(Entry e, long cumulativeLoadedMs) {
		long ticksPerBatch = e.cookTicksPerBatch();
		long batchMs = ticksPerBatch * 50L;
		int cooked = (int) Math.min((long) e.outputCountExpected(), cumulativeLoadedMs / batchMs);

		long totalBurnTicks = 0;
		for (FuelLoaded f : e.fuelLoaded()) totalBurnTicks += f.totalBurnTicks();
		long burnedTicks = (long) cooked * ticksPerBatch;
		if (cooked < e.inputCountInitial()) {
			long inFlightMs = Math.max(0L, cumulativeLoadedMs - (long) cooked * batchMs);
			burnedTicks += Math.min(ticksPerBatch, inFlightMs / 50L);
		}
		long remainingBurnTicks = Math.max(0L, totalBurnTicks - burnedTicks);
		int fuelRemainingBurns = (int) (remainingBurnTicks / ticksPerBatch);

		int remaining = e.inputCountInitial() - cooked;
		return new Snapshot(cooked, remaining, fuelRemainingBurns);
	}

	public record Snapshot(int countReady, int countRemaining, int fuelRemainingBurns) {}

	/** Compute target status given the entry and a fresh chunk/block observation. */
	public static Status deriveStatus(Entry e, boolean chunkLoaded, boolean blockIsFurnace) {
		// Terminal states stick until the surfacing handler drops them.
		if (e.status() == Status.DESTROYED || e.status() == Status.EMPTY) return e.status();
		if (!chunkLoaded) return Status.STALE;
		if (!blockIsFurnace) return Status.DESTROYED;
		Snapshot s = computeSnapshot(e, e.cumulativeLoadedMs());
		if (s.countReady() >= e.outputCountExpected()) {
			return e.outputCountExpected() < e.inputCountInitial() ? Status.PARTIAL : Status.READY;
		}
		return Status.COOKING;
	}

	/** Must be called on the client thread. */
	public static boolean chunkLoaded(BlockPos pos, ResourceKey<Level> dim) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) return false;
		if (!level.dimension().equals(dim)) return false;
		return level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
	}

	/** Must be called on the client thread. Caller should consult {@link #chunkLoaded} first. */
	public static boolean isStillFurnace(BlockPos pos) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) return false;
		return level.getBlockState(pos).is(Blocks.FURNACE);
	}

	/** Serialize an entry for the /smelt_status response. */
	public static Map<String, Object> toWire(Entry e) {
		Snapshot s = extrapolate(e);
		Map<String, Object> m = new LinkedHashMap<>();
		List<Object> posArr = new ArrayList<>(3);
		posArr.add(e.pos().getX());
		posArr.add(e.pos().getY());
		posArr.add(e.pos().getZ());
		m.put("furnace_pos", posArr);
		m.put("dimension", e.dimension().location().toString());

		Map<String, Object> input = new LinkedHashMap<>();
		input.put("id", e.inputItem().toString());
		input.put("count_remaining", s.countRemaining());
		m.put("input", input);

		Map<String, Object> output = new LinkedHashMap<>();
		output.put("id", e.resultItem().toString());
		output.put("count_ready", s.countReady());
		m.put("output", output);

		m.put("fuel_remaining_burns", s.fuelRemainingBurns());
		m.put("cook_progress", e.cookProgress());
		m.put("eta_seconds", e.etaSeconds());
		m.put("status", e.status().wire());

		if (e.status() == Status.STALE) {
			m.put("last_observed_ms", e.lastObservedMs());
		}
		return m;
	}
}
