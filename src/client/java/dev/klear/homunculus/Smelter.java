package dev.klear.homunculus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes a smelt against a placed furnace. Right-clicks the furnace to open the server-assigned
 * FurnaceMenu, then:
 *  - peeks all three slots (vanilla furnace BlockEntity items aren't synced to the client; opening
 *    the menu is the only way to see what's already there)
 *  - always evicts pre-existing result-slot contents to inventory so the cook count starts clean
 *  - calls {@link Smelts#evaluate} with the peeked stock, so matching pre-loaded input/fuel counts
 *    toward the budget instead of triggering missing_input/missing_fuel
 *  - evicts mismatched input/fuel (different item from plan) before pushing
 *  - shift-clicks input + fuel from inventory into slots 0/1, only the shortfall after pre-loaded
 *  - polls the result slot until it accumulates the target output, retrieves, closes
 *
 * Cleanup in finally: any leftover input/fuel/output gets shift-clicked back to inventory before
 * close, so a partial/aborted smelt doesn't strand items in the furnace.
 */
public final class Smelter {
	private Smelter() {}

	private static final long PER_OP_TIMEOUT_MS = 2000;
	private static final long CLICK_SETTLE_MS = 60;
	private static final long FINAL_SETTLE_MS = 150;
	private static final long MENU_OPEN_TIMEOUT_MS = 1500;
	private static final long MENU_POLL_INTERVAL_MS = 50;
	private static final long COOK_POLL_INTERVAL_MS = 250;
	private static final long COOK_DEADLINE_MIN_MS = 30_000;
	private static final long COOK_DEADLINE_CAP_MS = 5 * 60_000;

	public sealed interface Result permits Ok, ValidationFailure, ExecutionFailure {}

	public record Ok(String resultId, int resultCount, List<FuelUse> fuelUsed) implements Result {}

	/** One fuel type consumed by an Ok smelt — for the response's fuel_consumed array. */
	public record FuelUse(String id, int count) {}

	/** Budget validation failed AFTER opening the menu (with furnace contents factored in). */
	public record ValidationFailure(Smelts.Failure inner) implements Result {}

	/** Something glitched mid-execution (open timeout, click loop ran out of attempts, etc.). */
	public record ExecutionFailure(String message) implements Result {}

	private record SlotPeek(ItemStack input, ItemStack fuel, ItemStack result) {}

	public static Result execute(Smelts.PreOk pre, String requestFuel) throws InterruptedException {
		Minecraft mc = Minecraft.getInstance();

		ExecutionFailure preFail = supply(() -> {
			LocalPlayer p = mc.player;
			if (p == null) return new ExecutionFailure("no player (not in world)");
			if (mc.gameMode == null) return new ExecutionFailure("no gameMode");
			if (mc.level == null) return new ExecutionFailure("no level");
			if (p.containerMenu != p.inventoryMenu) {
				return new ExecutionFailure("another inventory screen is open; close it before smelting");
			}
			return null;
		});
		if (preFail != null) return preFail;

		// If pre-check found no furnace, evaluate will return requires_furnace; no menu work needed.
		if (!pre.furnaceNearby()) {
			Smelts.Result eval = supply(() -> Smelts.evaluate(pre, requestFuel, Smelts.ExtraStock.EMPTY));
			if (eval instanceof Smelts.Failure f) return new ValidationFailure(f);
			// Shouldn't reach here without a furnace, but be defensive.
			return new ExecutionFailure("furnace not nearby but evaluate returned Ok");
		}

		ExecutionFailure openFail = supply(() -> openFurnace(mc, pre.furnacePos()));
		if (openFail != null) return openFail;

		int containerId = waitForFurnaceMenu(mc, MENU_OPEN_TIMEOUT_MS);
		if (containerId < 0) {
			supply(() -> { closeContainerSafely(mc); return null; });
			return new ExecutionFailure("furnace at " + pre.furnacePos() + " did not open a FurnaceMenu within "
					+ MENU_OPEN_TIMEOUT_MS + "ms");
		}

		Result outcome;
		final boolean[] touched = {false};
		try {
			final int cid = containerId;
			SlotPeek peek = supply(() -> {
				LocalPlayer p = mc.player;
				if (p == null || p.containerMenu.containerId != cid) return null;
				AbstractContainerMenu menu = p.containerMenu;
				return new SlotPeek(
						menu.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem().copy(),
						menu.getSlot(AbstractFurnaceMenu.FUEL_SLOT).getItem().copy(),
						menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem().copy());
			});
			if (peek == null) {
				outcome = new ExecutionFailure("furnace menu vanished before peek");
				return outcome;
			}

			// Build ExtraStock from peeked input/fuel (read-only; nothing changed in furnace yet).
			Smelts.ExtraStock extra = Smelts.ExtraStock.fromFurnaceSlots(peek.input(), peek.fuel());

			// Run the full budget evaluation BEFORE touching the furnace, so a validation-only failure
			// (e.g. missing_input even with peek factored in) leaves the user's pre-loaded items alone.
			Smelts.Result eval = supply(() -> Smelts.evaluate(pre, requestFuel, extra));
			if (eval instanceof Smelts.Failure f) {
				outcome = new ValidationFailure(f);
				return outcome;
			}
			Smelts.Ok plan = (Smelts.Ok) eval;

			// Past this point we WILL modify furnace state; cleanup-finally is now responsible for
			// returning anything left over to the player's inventory.
			touched[0] = true;

			// Always evict pre-existing result so cook count starts at 0 and got = afterOutput - beforeOutput is clean.
			if (!peek.result().isEmpty()) {
				supply(() -> {
					LocalPlayer p = mc.player;
					if (p != null && p.containerMenu.containerId == cid) {
						mc.gameMode.handleInventoryMouseClick(cid,
								AbstractFurnaceMenu.RESULT_SLOT, 0, ClickType.QUICK_MOVE, p);
					}
					return null;
				});
				Thread.sleep(CLICK_SETTLE_MS);
			}

			Item resultItem = BuiltInRegistries.ITEM.getValue(pre.resultItem());
			int beforeOutput = supply(() -> countItem(mc.player, resultItem));

			Item inputItem = BuiltInRegistries.ITEM.getValue(plan.inputItem());
			Item firstFuelItem = plan.fuelPlan().isEmpty()
					? null
					: BuiltInRegistries.ITEM.getValue(plan.fuelPlan().get(0).id());

			// Evict mismatched input — shift-click on a furnace slot routes contents to inventory.
			if (!peek.input().isEmpty() && peek.input().getItem() != inputItem) {
				supply(() -> {
					LocalPlayer p = mc.player;
					if (p != null && p.containerMenu.containerId == cid) {
						mc.gameMode.handleInventoryMouseClick(cid,
								AbstractFurnaceMenu.INGREDIENT_SLOT, 0, ClickType.QUICK_MOVE, p);
					}
					return null;
				});
				Thread.sleep(CLICK_SETTLE_MS);
			}
			// Evict mismatched fuel — only if the pre-loaded fuel doesn't match the first component.
			if (!peek.fuel().isEmpty() && peek.fuel().getItem() != firstFuelItem) {
				supply(() -> {
					LocalPlayer p = mc.player;
					if (p != null && p.containerMenu.containerId == cid) {
						mc.gameMode.handleInventoryMouseClick(cid,
								AbstractFurnaceMenu.FUEL_SLOT, 0, ClickType.QUICK_MOVE, p);
					}
					return null;
				});
				Thread.sleep(CLICK_SETTLE_MS);
			}

			int inputToPush = plan.inputToPush();
			if (inputToPush > 0) {
				ExecutionFailure pushInput = pushIntoSlot(mc, cid, inputItem,
						AbstractFurnaceMenu.INGREDIENT_SLOT, inputToPush);
				if (pushInput != null) { outcome = pushInput; return outcome; }
				Thread.sleep(CLICK_SETTLE_MS);
			}

			// Push the first fuel component immediately. Subsequent components are pushed inside
			// the cook poll loop, each time the fuel slot becomes empty.
			List<Smelts.FuelComponent> components = plan.fuelPlan();
			if (!components.isEmpty() && components.get(0).toPush() > 0) {
				Item fuel0 = BuiltInRegistries.ITEM.getValue(components.get(0).id());
				ExecutionFailure pf = pushIntoSlot(mc, cid, fuel0,
						AbstractFurnaceMenu.FUEL_SLOT, components.get(0).toPush());
				if (pf != null) { outcome = pf; return outcome; }
				Thread.sleep(CLICK_SETTLE_MS);
			}

			long deadline = computeCookDeadline(plan);
			int cooked = pollUntilCookedMulti(mc, cid, plan.totalOutput(), components, deadline);
			if (cooked < 0) {
				outcome = new ExecutionFailure("furnace menu closed unexpectedly mid-cook");
				return outcome;
			}
			if (cooked < plan.totalOutput()) {
				outcome = new ExecutionFailure("cook timed out: expected " + plan.totalOutput() + " "
						+ plan.resultItem() + " in result slot, got " + cooked);
				return outcome;
			}

			supply(() -> {
				LocalPlayer p = mc.player;
				if (p != null && p.containerMenu.containerId == cid) {
					ItemStack r = p.containerMenu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem();
					if (!r.isEmpty()) {
						mc.gameMode.handleInventoryMouseClick(cid,
								AbstractFurnaceMenu.RESULT_SLOT, 0, ClickType.QUICK_MOVE, p);
					}
				}
				return null;
			});
			Thread.sleep(FINAL_SETTLE_MS);

			int afterOutput = supply(() -> countItem(mc.player, resultItem));
			int got = afterOutput - beforeOutput;
			if (got >= plan.totalOutput()) {
				List<FuelUse> fuelUsed = new ArrayList<>(components.size());
				for (Smelts.FuelComponent c : components) {
					fuelUsed.add(new FuelUse(c.id().toString(), c.pieces()));
				}
				outcome = new Ok(plan.resultItem().toString(), plan.totalOutput(), fuelUsed);
			} else {
				outcome = new ExecutionFailure("expected " + plan.totalOutput() + " " + plan.resultItem()
						+ " in inventory after smelt, got " + got);
			}
			return outcome;
		} finally {
			supply(() -> {
				LocalPlayer p = mc.player;
				if (p != null && p.containerMenu != p.inventoryMenu
						&& p.containerMenu.containerId == containerId) {
					// Only evict if we modified the furnace. On validation-only failure we leave
					// pre-loaded items where the user put them.
					if (touched[0]) {
						int cid = p.containerMenu.containerId;
						for (int slot : new int[]{AbstractFurnaceMenu.RESULT_SLOT,
								AbstractFurnaceMenu.INGREDIENT_SLOT,
								AbstractFurnaceMenu.FUEL_SLOT}) {
							ItemStack stk = p.containerMenu.getSlot(slot).getItem();
							if (!stk.isEmpty()) {
								mc.gameMode.handleInventoryMouseClick(cid, slot, 0, ClickType.QUICK_MOVE, p);
							}
						}
					}
				}
				closeContainerSafely(mc);
				return null;
			});
		}
	}

	private static long computeCookDeadline(Smelts.Ok plan) {
		long expected = (long) plan.batches() * plan.cookTicks() * 50L;
		long withSafety = (long) (expected * 1.5) + 5_000L;
		long capped = Math.min(withSafety, COOK_DEADLINE_CAP_MS);
		long minimum = COOK_DEADLINE_MIN_MS;
		return System.currentTimeMillis() + Math.max(minimum, capped);
	}

	/**
	 * Shift-clicks {@code needed} pieces of {@code item} from the player-inventory portion of the
	 * open container into {@code targetSlot}. Tracks inventory drain rather than the target slot's
	 * count: when pushing fuel, the furnace can consume already-deposited pieces during the
	 * settle-between-clicks delay, which would falsely look like underfill if measured at the
	 * target. Drain-tracking counts a piece as "transferred" the moment it leaves inventory,
	 * regardless of whether it's still in the slot or already burning.
	 */
	private static ExecutionFailure pushIntoSlot(Minecraft mc, int containerId, Item item,
												  int targetSlot, int needed) throws InterruptedException {
		final int FURNACE_SLOTS = AbstractFurnaceMenu.SLOT_COUNT;
		final int INV_END = FURNACE_SLOTS + 36;
		final int MAX_ATTEMPTS = 16;

		Integer initial = supply(() -> {
			LocalPlayer p = mc.player;
			if (p == null) return null;
			AbstractContainerMenu menu = p.containerMenu;
			if (menu.containerId != containerId) return null;
			return countInInvPortion(menu, item, FURNACE_SLOTS, INV_END);
		});
		if (initial == null) {
			return new ExecutionFailure("menu vanished before pushing " + BuiltInRegistries.ITEM.getKey(item));
		}
		final int initialInvCount = initial;

		for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
			int status = supply(() -> {
				LocalPlayer p = mc.player;
				if (p == null) return -2;
				AbstractContainerMenu menu = p.containerMenu;
				if (menu.containerId != containerId) return -2;

				int currentInv = 0;
				int sourceSlot = -1;
				for (int s = FURNACE_SLOTS; s < INV_END; s++) {
					ItemStack stk = menu.getSlot(s).getItem();
					if (!stk.isEmpty() && stk.getItem() == item) {
						currentInv += stk.getCount();
						if (sourceSlot < 0) sourceSlot = s;
					}
				}
				int transferred = initialInvCount - currentInv;
				if (transferred >= needed) return 0;
				if (sourceSlot < 0) return -1;

				mc.gameMode.handleInventoryMouseClick(containerId, sourceSlot, 0, ClickType.QUICK_MOVE, p);
				return 1;
			});

			if (status == 0) return null;
			if (status == -1) {
				return new ExecutionFailure("ran out of " + BuiltInRegistries.ITEM.getKey(item)
						+ " in inventory before " + needed + " were transferred to slot " + targetSlot);
			}
			if (status == -2) {
				return new ExecutionFailure("player or furnace menu vanished while pushing "
						+ BuiltInRegistries.ITEM.getKey(item));
			}
			Thread.sleep(CLICK_SETTLE_MS);
		}
		return new ExecutionFailure("could not push " + needed + " " + BuiltInRegistries.ITEM.getKey(item)
				+ " into slot " + targetSlot + " after " + MAX_ATTEMPTS + " attempts");
	}

	private static int countInInvPortion(AbstractContainerMenu menu, Item item, int from, int toExcl) {
		int total = 0;
		for (int s = from; s < toExcl; s++) {
			ItemStack stk = menu.getSlot(s).getItem();
			if (!stk.isEmpty() && stk.getItem() == item) total += stk.getCount();
		}
		return total;
	}

	/**
	 * Polls the result slot until {@code wanted} pieces have cooked, OR the deadline expires. While
	 * polling, also pushes the next fuel component each time the fuel slot becomes empty. The first
	 * component (index 0) is pushed by the caller before entering this loop; index 1+ are pushed
	 * here.
	 */
	private static int pollUntilCookedMulti(Minecraft mc, int containerId, int wanted,
											 List<Smelts.FuelComponent> components,
											 long deadlineMs) throws InterruptedException {
		int nextComponent = components.size() > 1 ? 1 : components.size();  // index of next unpushed
		while (System.currentTimeMillis() < deadlineMs) {
			SlotPoll poll = supply(() -> {
				LocalPlayer p = mc.player;
				if (p == null) return null;
				AbstractContainerMenu menu = p.containerMenu;
				if (menu.containerId != containerId) return null;
				ItemStack r = menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem();
				ItemStack f = menu.getSlot(AbstractFurnaceMenu.FUEL_SLOT).getItem();
				return new SlotPoll(r.isEmpty() ? 0 : r.getCount(), f.isEmpty());
			});
			if (poll == null) return -1;
			if (poll.cooked() >= wanted) return poll.cooked();

			if (nextComponent < components.size() && poll.fuelEmpty()) {
				Smelts.FuelComponent comp = components.get(nextComponent);
				int toPush = comp.toPush();
				if (toPush > 0) {
					Item fuelItem = BuiltInRegistries.ITEM.getValue(comp.id());
					ExecutionFailure pf = pushIntoSlot(mc, containerId, fuelItem,
							AbstractFurnaceMenu.FUEL_SLOT, toPush);
					if (pf != null) {
						// Push failed — exit polling early; caller will report cook-timeout.
						HomunculusClient.LOGGER.warn("smelt: failed to push fuel component {}: {}",
								nextComponent, pf.message());
						return poll.cooked();
					}
				}
				nextComponent++;
			}
			Thread.sleep(COOK_POLL_INTERVAL_MS);
		}
		return supply(() -> {
			LocalPlayer p = mc.player;
			if (p == null || p.containerMenu.containerId != containerId) return 0;
			ItemStack r = p.containerMenu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).getItem();
			return r.isEmpty() ? 0 : r.getCount();
		});
	}

	private record SlotPoll(int cooked, boolean fuelEmpty) {}

	private static ExecutionFailure openFurnace(Minecraft mc, BlockPos pos) {
		LocalPlayer p = mc.player;
		if (p == null || mc.gameMode == null) {
			return new ExecutionFailure("player vanished before opening furnace");
		}
		if (p.isShiftKeyDown()) {
			p.setShiftKeyDown(false);
			p.connection.send(new ServerboundPlayerCommandPacket(
					p, ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
		}
		Look.faceBlockTop(mc, pos);
		Vec3 hitLoc = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
		BlockHitResult bhit = new BlockHitResult(hitLoc, Direction.UP, pos, false);
		mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, bhit);
		return null;
	}

	private static int waitForFurnaceMenu(Minecraft mc, long timeoutMs) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			Integer id = supply(() -> {
				LocalPlayer p = mc.player;
				if (p == null) return null;
				AbstractContainerMenu menu = p.containerMenu;
				return (menu instanceof FurnaceMenu) ? Integer.valueOf(menu.containerId) : null;
			});
			if (id != null) return id;
			Thread.sleep(MENU_POLL_INTERVAL_MS);
		}
		return -1;
	}

	private static void closeContainerSafely(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p != null && p.containerMenu != p.inventoryMenu) {
			p.closeContainer();
		}
	}

	private static <T> T supply(java.util.function.Supplier<T> task) {
		try {
			return ClientThread.supply(task).get(PER_OP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			throw new RuntimeException("client-thread op timed out", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("interrupted", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException re) throw re;
			throw new RuntimeException(cause);
		}
	}

	private static int countItem(LocalPlayer p, Item item) {
		Inventory inv = p.getInventory();
		int total = 0;
		total += sumOf(inv.items, item);
		total += sumOf(inv.armor, item);
		total += sumOf(inv.offhand, item);
		return total;
	}

	private static int sumOf(NonNullList<ItemStack> stacks, Item item) {
		int total = 0;
		for (ItemStack s : stacks) {
			if (!s.isEmpty() && s.getItem() == item) total += s.getCount();
		}
		return total;
	}
}
