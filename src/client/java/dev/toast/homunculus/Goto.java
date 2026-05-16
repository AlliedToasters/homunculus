package dev.toast.homunculus;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.event.events.PathEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalYLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Synchronous goto utility — drives Baritone's customGoalProcess to a block target and waits for
 * the terminal signal. Used by {@link GotoHandler} (external /baritone/goto) and by
 * {@link CollectSmeltHandler} (internal routing to a registered furnace).
 *
 * <p>Acquires {@link Baritone#SESSION_LOCK} for the duration; returns {@link Failed} with reason
 * {@code busy} if another /baritone/* call is in flight. The lock is released before returning.
 *
 * <p>On any non-arrived terminal, calls {@code pathingBehavior.cancelEverything()} before
 * releasing the lock — callers can assume Baritone is idle when this returns.
 *
 * <p>Settings overrides ({@code allowPlace}, {@code acceptableThrowawayItems}) and the slot-6
 * hotbar stage are snapshot before the goto and restored on lock release, guaranteed by a
 * try/finally. Baritone settings are global; the session lock guarantees serial access.
 */
public final class Goto {
	private Goto() {}

	public static final long HARD_CAP_SECONDS = 300;
	private static final long GAME_THREAD_TIMEOUT_MS = 5_000;
	private static final int HOTBAR_THROWAWAY_SLOT = 6;
	private static final int HOTBAR_THROWAWAY_MENU_SLOT = 36 + HOTBAR_THROWAWAY_SLOT;
	private static final long SWAP_SETTLE_MS = 100;

	/**
	 * Which Baritone {@link Goal} this call drives. See SPEC §`/baritone/goto`.
	 * <ul>
	 *   <li>{@link #BLOCK} — {@code GoalBlock(x, y, z)}. Arrival is "within {@code arrival_tolerance}
	 *       Manhattan of the point" (near-miss tolerance for Baritone landing slightly off-column) OR
	 *       {@code PathEvent.AT_GOAL}.</li>
	 *   <li>{@link #Y_LEVEL} — {@code GoalYLevel(y)}. Arrival is {@code PathEvent.AT_GOAL} only.
	 *       The position predicate is deliberately omitted: the player traverses through the target
	 *       y-plane mid-route, so a tolerance-based predicate would misfire and strand the player
	 *       at the wrong y. Baritone's own {@code isInGoal} already defines arrival correctly.</li>
	 * </ul>
	 */
	public enum GoalType { BLOCK, Y_LEVEL }

	public sealed interface Outcome permits Arrived, Failed {
		double[] finalPosition();
	}

	public record Arrived(String message, double[] finalPosition) implements Outcome {}

	public record Failed(String reason, String message, double[] finalPosition) implements Outcome {}

	/**
	 * Per-call configuration. See SPEC §`/baritone/goto`.
	 *
	 * @param allowPlace when {@code false}, force Baritone's global {@code allowPlace} to false for
	 *     the duration. Default true.
	 * @param throwawayItems when non-null, restrict {@code acceptableThrowawayItems} to this list
	 *     (raw item ids; unknowns logged + skipped). {@code null} = Baritone default. Empty list
	 *     collapses to no-placement semantics. Default null.
	 * @param ensureThrowawayInHotbar when true, stage the most-plentiful matching throwaway item
	 *     into hotbar slot 6 before pathing; restore on exit. Default false.
	 */
	public record Options(boolean allowPlace, List<String> throwawayItems, boolean ensureThrowawayInHotbar) {
		public static Options defaults() {
			return new Options(true, null, false);
		}
	}

	public static Outcome run(int x, int y, int z, long timeoutSeconds, long arrivalTolerance) {
		return run(GoalType.BLOCK, x, y, z, timeoutSeconds, arrivalTolerance, Options.defaults());
	}

	public static Outcome run(int x, int y, int z, long timeoutSeconds, long arrivalTolerance, boolean allowPlace) {
		return run(GoalType.BLOCK, x, y, z, timeoutSeconds, arrivalTolerance,
				new Options(allowPlace, null, false));
	}

	public static Outcome run(GoalType goalType, int x, int y, int z, long timeoutSeconds, long arrivalTolerance, Options opts) {
		if (!Baritone.isApiLoaded()) {
			return new Failed("baritone_not_loaded", "Baritone API not present at runtime", null);
		}

		if (!Baritone.SESSION_LOCK.tryLock()) {
			return new Failed("busy", "another /baritone/* call is in flight", null);
		}

		try {
			return runLocked(goalType, x, y, z, timeoutSeconds, arrivalTolerance, opts);
		} finally {
			Baritone.SESSION_LOCK.unlock();
		}
	}

	private static Outcome runLocked(GoalType goalType, int x, int y, int z, long timeoutSeconds, long arrivalTolerance, Options opts) {
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

		Snapshot snapshot;
		try {
			snapshot = applyOverrides(opts);
		} catch (Exception e) {
			return new Failed("internal_error", "failed to apply overrides: " + rootMessage(e), null);
		}

		Goal goal = switch (goalType) {
			case BLOCK -> new GoalBlock(x, y, z);
			case Y_LEVEL -> new GoalYLevel(y);
		};

		double[] initialPos;
		try {
			initialPos = ClientThread.supply(() -> {
				IBaritone bar = BaritoneAPI.getProvider().getPrimaryBaritone();
				if (bar == null) throw new IllegalStateException("no primary Baritone (player not in world?)");
				LocalPlayer p = Minecraft.getInstance().player;
				if (p == null) throw new IllegalStateException("no player");
				bar.getGameEventHandler().registerEventListener(listener);
				bar.getCustomGoalProcess().setGoalAndPath(goal);
				return new double[] { p.getX(), p.getY(), p.getZ() };
			}).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			closed.set(true);
			restoreOverrides(snapshot);
			return new Failed("internal_error", "failed to start goto: " + rootMessage(e), null);
		}

		long timeoutMs = Math.min(timeoutSeconds, HARD_CAP_SECONDS) * 1000L;
		long deadline = System.currentTimeMillis() + timeoutMs;

		double[] lastPos = initialPos;
		try {
			while (true) {
				long wait = deadline - System.currentTimeMillis();
				if (wait <= 0) {
					return new Failed("timeout", "deadline elapsed without arrival", lastPos);
				}
				Signal sig = queue.poll(wait, TimeUnit.MILLISECONDS);
				if (sig == null) continue;

				if (sig instanceof TickSignal t) {
					lastPos = new double[] { t.x, t.y, t.z };
					if (goalType == GoalType.BLOCK) {
						// Position-predicate arrival earns its keep for BLOCK as a near-miss tolerance
						// — Baritone sometimes lands slightly off the requested column.
						double dist = manhattan(lastPos, x, y, z);
						if (dist <= arrivalTolerance) {
							return new Arrived("arrived at target within tolerance " + arrivalTolerance, lastPos);
						}
						if (!t.active && !t.pathing) {
							return new Failed("stuck",
									"Baritone idled with " + String.format("%.2f", dist) + " blocks remaining", lastPos);
						}
					} else {
						// Y_LEVEL: no position-predicate (it would misfire mid-route as the player
						// passes through the target y-plane). Arrival is delegated to PathEvent.AT_GOAL.
						// Still detect stuck via process state.
						if (!t.active && !t.pathing) {
							double dy = Math.abs(lastPos[1] - y);
							return new Failed("stuck",
									"Baritone idled at y=" + String.format("%.2f", lastPos[1])
											+ " (target y=" + y + ", Δy=" + String.format("%.2f", dy) + ")", lastPos);
						}
					}
				} else if (sig instanceof PathSignal p) {
					switch (p.event) {
						case CALC_FAILED -> {
							return new Failed("unreachable", "Baritone reported PathEvent.CALC_FAILED", lastPos);
						}
						case AT_GOAL -> {
							return new Arrived("Baritone reported PathEvent.AT_GOAL", lastPos);
						}
						case CANCELED -> {
							return new Failed("canceled", "Baritone reported PathEvent.CANCELED", lastPos);
						}
						default -> {}
					}
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Failed("internal_error", "interrupted", lastPos);
		} finally {
			closed.set(true);
			try {
				ClientThread.supply(() -> {
					IBaritone bar = BaritoneAPI.getProvider().getPrimaryBaritone();
					if (bar != null) bar.getPathingBehavior().cancelEverything();
					return null;
				}).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
			} catch (Exception e) {
				HomunculusClient.LOGGER.warn("Goto cleanup cancelEverything threw", e);
			}
			restoreOverrides(snapshot);
		}
	}

	/* ============================ Overrides ============================ */

	/**
	 * Captured pre-call state that must be restored on lock release. Any field being non-null/non-(-1)
	 * indicates an override was applied for that knob.
	 */
	private record Snapshot(
			Boolean priorAllowPlace,
			List<Item> priorThrowaway,
			int slot6SourceMenuSlot) {
	}

	private static Snapshot applyOverrides(Options opts) throws Exception {
		// Resolve throwaway item list once — needed for both the Baritone-settings override and the
		// hotbar-staging selection. Null = use Baritone's current defaults for selection too.
		List<Item> resolvedThrowaway = opts.throwawayItems() == null
				? null
				: resolveItems(opts.throwawayItems());

		final Boolean priorAllowPlace;
		final List<Item> priorThrowaway;
		try {
			Object[] result = ClientThread.supply(() -> {
				Boolean pa = null;
				List<Item> pt = null;
				if (!opts.allowPlace()) {
					pa = BaritoneAPI.getSettings().allowPlace.value;
					BaritoneAPI.getSettings().allowPlace.value = false;
				}
				if (resolvedThrowaway != null) {
					pt = new ArrayList<>(BaritoneAPI.getSettings().acceptableThrowawayItems.value);
					BaritoneAPI.getSettings().acceptableThrowawayItems.value = new ArrayList<>(resolvedThrowaway);
				}
				return new Object[] { pa, pt };
			}).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
			priorAllowPlace = (Boolean) result[0];
			@SuppressWarnings("unchecked")
			List<Item> pt = (List<Item>) result[1];
			priorThrowaway = pt;
		} catch (Exception e) {
			throw new Exception("settings override failed: " + rootMessage(e), e);
		}

		int sourceMenuSlot = -1;
		if (opts.ensureThrowawayInHotbar()) {
			// Selection set: caller-provided throwaway list if any (already resolved above), else
			// Baritone's *current* defaults (we read .value rather than the static default because
			// other settings overrides may have changed it).
			List<Item> selectionSet = resolvedThrowaway;
			if (selectionSet == null) {
				try {
					selectionSet = ClientThread.supply(
							() -> new ArrayList<>(BaritoneAPI.getSettings().acceptableThrowawayItems.value))
							.get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
				} catch (Exception e) {
					HomunculusClient.LOGGER.warn("could not read acceptableThrowawayItems for hotbar staging", e);
					selectionSet = List.of();
				}
			}
			if (!selectionSet.isEmpty()) {
				try {
					sourceMenuSlot = stageThrowawayInSlot6(selectionSet, opts.throwawayItems());
				} catch (Exception e) {
					HomunculusClient.LOGGER.warn("ensure_throwaway_in_hotbar failed; proceeding without stage", e);
				}
			}
		}

		return new Snapshot(priorAllowPlace, priorThrowaway, sourceMenuSlot);
	}

	private static void restoreOverrides(Snapshot s) {
		if (s == null) return;
		try {
			ClientThread.supply(() -> {
				if (s.priorAllowPlace() != null) {
					BaritoneAPI.getSettings().allowPlace.value = s.priorAllowPlace();
				}
				if (s.priorThrowaway() != null) {
					BaritoneAPI.getSettings().acceptableThrowawayItems.value = s.priorThrowaway();
				}
				return null;
			}).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			HomunculusClient.LOGGER.warn("failed to restore Baritone settings", e);
		}
		if (s.slot6SourceMenuSlot() >= 0) {
			try {
				int src = s.slot6SourceMenuSlot();
				ClientThread.supply(() -> {
					Minecraft mc = Minecraft.getInstance();
					if (mc.player == null || mc.gameMode == null) return null;
					// Symmetric swap: the original slot-6 stack lives at `src` (Baritone consumes
					// from slot 6, not from arbitrary inventory). Swapping src↔hotbar6 puts the
					// original back into slot 6; whatever throwaway remains in slot 6 goes to src.
					mc.gameMode.handleInventoryMouseClick(
							InventoryMenu.CONTAINER_ID, src, HOTBAR_THROWAWAY_SLOT,
							ClickType.SWAP, mc.player);
					return null;
				}).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
				Thread.sleep(SWAP_SETTLE_MS);
			} catch (Exception e) {
				HomunculusClient.LOGGER.warn("failed to restore hotbar slot 6", e);
			}
		}
	}

	/**
	 * Pick the most-plentiful matching stack and swap it into hotbar slot 6.
	 * Returns the source menu slot (for later restore), or -1 if no swap was performed.
	 */
	private static int stageThrowawayInSlot6(List<Item> selectionSet, List<String> tieOrder) throws Exception {
		return ClientThread.supply(() -> {
			Minecraft mc = Minecraft.getInstance();
			LocalPlayer p = mc.player;
			if (p == null) return -1;
			Inventory inv = p.getInventory();

			// Sum total counts per item across all 36 inv slots; track the largest single stack
			// per item for the eventual SWAP source.
			Map<Item, Integer> totals = new HashMap<>();
			Map<Item, Integer> bestSlotPerItem = new HashMap<>();
			Map<Item, Integer> bestCountPerItem = new HashMap<>();
			for (int i = 0; i < 36; i++) {
				ItemStack stack = inv.items.get(i);
				if (stack.isEmpty()) continue;
				Item it = stack.getItem();
				if (!selectionSet.contains(it)) continue;
				int menuSlot = (i < 9) ? 36 + i : i;
				totals.merge(it, stack.getCount(), Integer::sum);
				if (stack.getCount() > bestCountPerItem.getOrDefault(it, -1)) {
					bestCountPerItem.put(it, stack.getCount());
					bestSlotPerItem.put(it, menuSlot);
				}
			}
			if (totals.isEmpty()) {
				HomunculusClient.LOGGER.info("ensure_throwaway_in_hotbar: no matching items in inventory");
				return -1;
			}

			// Pick the winner: highest total count, tiebreak by caller's `throwaway_items` order
			// if provided, else alphabetical item id.
			List<Item> tieOrderResolved = tieOrder == null ? null : resolveItems(tieOrder);
			Item winner = null;
			int winnerTotal = -1;
			for (Map.Entry<Item, Integer> e : totals.entrySet()) {
				if (e.getValue() > winnerTotal) {
					winner = e.getKey();
					winnerTotal = e.getValue();
				} else if (e.getValue() == winnerTotal && winner != null) {
					if (compareForTiebreak(e.getKey(), winner, tieOrderResolved) < 0) {
						winner = e.getKey();
					}
				}
			}
			if (winner == null) return -1;

			int sourceMenuSlot = bestSlotPerItem.get(winner);
			if (sourceMenuSlot == HOTBAR_THROWAWAY_MENU_SLOT) {
				// Already in slot 6 — nothing to swap, nothing to restore.
				return -1;
			}

			mc.gameMode.handleInventoryMouseClick(
					InventoryMenu.CONTAINER_ID, sourceMenuSlot, HOTBAR_THROWAWAY_SLOT,
					ClickType.SWAP, mc.player);
			return sourceMenuSlot;
		}).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
	}

	private static int compareForTiebreak(Item a, Item b, List<Item> tieOrder) {
		if (tieOrder != null) {
			int ia = tieOrder.indexOf(a);
			int ib = tieOrder.indexOf(b);
			if (ia >= 0 && ib >= 0) return Integer.compare(ia, ib);
			if (ia >= 0) return -1;
			if (ib >= 0) return 1;
		}
		ResourceLocation ra = BuiltInRegistries.ITEM.getKey(a);
		ResourceLocation rb = BuiltInRegistries.ITEM.getKey(b);
		return ra.toString().compareTo(rb.toString());
	}

	/** Resolve a list of namespaced item ids, skipping unknowns with a warning. */
	private static List<Item> resolveItems(List<String> ids) {
		List<Item> out = new ArrayList<>(ids.size());
		for (String raw : ids) {
			ResourceLocation rl = ResourceLocation.tryParse(raw);
			if (rl == null) {
				HomunculusClient.LOGGER.warn("ignoring unparseable item id in throwaway_items: '{}'", raw);
				continue;
			}
			if (!BuiltInRegistries.ITEM.containsKey(rl)) {
				HomunculusClient.LOGGER.warn("ignoring unknown item id in throwaway_items: '{}'", raw);
				continue;
			}
			Item it = BuiltInRegistries.ITEM.getValue(rl);
			if (!out.contains(it)) out.add(it);
		}
		return Collections.unmodifiableList(out);
	}

	private static double manhattan(double[] pos, int tx, int ty, int tz) {
		return Math.abs(pos[0] - tx) + Math.abs(pos[1] - ty) + Math.abs(pos[2] - tz);
	}

	private static String rootMessage(Throwable t) {
		Throwable c = t.getCause() != null ? t.getCause() : t;
		String m = c.getMessage();
		return m == null ? c.getClass().getSimpleName() : m;
	}

	private sealed interface Signal {}
	private record TickSignal(boolean active, boolean pathing, double x, double y, double z) implements Signal {}
	private record PathSignal(PathEvent event) implements Signal {}
}
