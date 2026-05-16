package dev.toast.homunculus;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.List;

/**
 * Per-tick updater for {@link FurnaceRegistry}. Runs on the client tick handler:
 * <ol>
 *   <li>For each registered entry, samples whether its chunk is loaded and whether the block is
 *       still a furnace.</li>
 *   <li>Advances {@code cumulativeLoadedMs} by one tick (50ms) when the chunk is loaded.</li>
 *   <li>Recomputes status via {@link FurnaceRegistry#deriveStatus} and replaces the entry.</li>
 *   <li>Updates lastKnown counts so /smelt_status renders the latest extrapolation when the chunk
 *       is loaded, or the frozen snapshot when stale.</li>
 * </ol>
 *
 * <p>Per-tick (20Hz) iteration over the registry is fine — bounded entry count, all reads are
 * pure-Java (no NBT, no remote calls).
 *
 * <p>Note: this is NOT where the cook actually progresses — vanilla MC ticks the furnace
 * BlockEntity server-side. This handler only maintains the registry's *belief* about progress so
 * /smelt_status can return meaningful data without opening the furnace menu.
 */
public final class FurnaceTicker {
	private FurnaceTicker() {}

	private static final long TICK_MS = 50L;

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(FurnaceTicker::onTick);
	}

	private static void onTick(Minecraft mc) {
		if (FurnaceRegistry.size() == 0) return;
		ClientLevel level = mc.level;
		// No world loaded: leave entries alone; they'll reconcile when world reloads.
		if (level == null) return;

		long now = System.currentTimeMillis();
		List<FurnaceRegistry.Entry> entries = FurnaceRegistry.all();
		for (FurnaceRegistry.Entry e : entries) {
			if (e.status() == FurnaceRegistry.Status.DESTROYED
					|| e.status() == FurnaceRegistry.Status.EMPTY) {
				// Terminal — the surfacing handler drops them.
				continue;
			}

			boolean chunkLoaded = FurnaceRegistry.chunkLoaded(e.pos(), e.dimension());
			boolean blockIsFurnace = chunkLoaded && FurnaceRegistry.isStillFurnace(e.pos());

			long newCumulative = chunkLoaded
					? e.cumulativeLoadedMs() + TICK_MS
					: e.cumulativeLoadedMs();

			FurnaceRegistry.Entry probe = e.with(e.status(), newCumulative, now,
					e.lastKnownCountReady(),
					e.lastKnownCountRemaining(),
					e.lastKnownFuelRemainingBurns());

			FurnaceRegistry.Status nextStatus =
					FurnaceRegistry.deriveStatus(probe, chunkLoaded, blockIsFurnace);

			// Compute snapshot for whichever clock applies. When stale, freeze counts at last-known.
			int countReady, countRemaining, fuelRemainingBurns;
			long observedMs;
			if (nextStatus == FurnaceRegistry.Status.STALE) {
				countReady = e.lastKnownCountReady();
				countRemaining = e.lastKnownCountRemaining();
				fuelRemainingBurns = e.lastKnownFuelRemainingBurns();
				observedMs = e.lastObservedMs();  // don't update — stale means "no fresh observation"
			} else {
				FurnaceRegistry.Snapshot s = FurnaceRegistry.computeSnapshot(probe, newCumulative);
				countReady = s.countReady();
				countRemaining = s.countRemaining();
				fuelRemainingBurns = s.fuelRemainingBurns();
				observedMs = now;
			}

			// Skip the write if nothing meaningful changed (saves a CHM put per tick when idle).
			boolean changed = nextStatus != e.status()
					|| newCumulative != e.cumulativeLoadedMs()
					|| countReady != e.lastKnownCountReady()
					|| countRemaining != e.lastKnownCountRemaining()
					|| fuelRemainingBurns != e.lastKnownFuelRemainingBurns()
					|| observedMs != e.lastObservedMs();
			if (!changed) continue;

			FurnaceRegistry.put(e.with(nextStatus, newCumulative, observedMs,
					countReady, countRemaining, fuelRemainingBurns));
		}
	}
}
