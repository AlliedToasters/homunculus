package dev.toast.homunculus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.FuelValues;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Loads a furnace with input + fuel and lights it ({@link #ignite}), or opens a furnace and pulls
 * the output ({@link #collectFromFurnace}). Both close the menu before returning. Neither blocks
 * for the cook itself — that runs asynchronously on the server, and the registry/ticker tracks
 * progress without keeping the menu open.
 *
 * <p>Single-fuel-type restriction (v1.2). The plan from {@link Smelts#evaluate} may include
 * multiple fuel components when one tier alone doesn't cover the burn budget. For the async path
 * we only push the FIRST component and cap the input batch at what that fuel can cook. Any extra
 * input the agent asked for is left in inventory; the response reports the actual loaded count and
 * leaves the agent to retry with topped-up fuel.
 */
public final class Smelter {
	private Smelter() {}

	private static final long PER_OP_TIMEOUT_MS = 2000;
	private static final long CLICK_SETTLE_MS = 60;
	private static final long FINAL_SETTLE_MS = 150;
	private static final long MENU_OPEN_TIMEOUT_MS = 1500;
	private static final long MENU_POLL_INTERVAL_MS = 50;

	// ── ignite ─────────────────────────────────────────────────────────────

	public sealed interface IgniteResult permits IgniteOk, ValidationFailure, ExecutionFailure {}

	public record IgniteOk(
			BlockPos furnacePos,
			ResourceLocation inputItem,
			int inputLoaded,
			ResourceLocation resultItem,
			int outputExpected,
			List<FurnaceRegistry.FuelLoaded> fuelLoaded,
			int cookTicksPerBatch,
			long startedAtMs,
			int requestedBatches             // what the agent asked for (may be > inputLoaded)
	) implements IgniteResult {
		public boolean fuelCapped() { return inputLoaded < requestedBatches; }
	}

	public record ValidationFailure(Smelts.Failure inner) implements IgniteResult {}

	public record ExecutionFailure(String message) implements IgniteResult {}

	/**
	 * Open the furnace, evict any stale contents, push input + first fuel component, close the menu.
	 * Returns the loaded plan so the caller can register it. Does NOT wait for cook completion.
	 */
	public static IgniteResult ignite(Smelts.PreOk pre, String requestFuel) throws InterruptedException {
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

		if (!pre.furnaceNearby()) {
			// Caller should have auto-placed by now; defensive return.
			Smelts.Result eval = supply(() -> Smelts.evaluate(pre, requestFuel, Smelts.ExtraStock.EMPTY));
			if (eval instanceof Smelts.Failure f) return new ValidationFailure(f);
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

		IgniteResult outcome = null;
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

			Smelts.ExtraStock extra = Smelts.ExtraStock.fromFurnaceSlots(peek.input(), peek.fuel());
			Smelts.Result eval = supply(() -> Smelts.evaluate(pre, requestFuel, extra));
			if (eval instanceof Smelts.Failure f) {
				outcome = new ValidationFailure(f);
				return outcome;
			}
			Smelts.Ok plan = (Smelts.Ok) eval;

			// Fuel-cap: only push the first component; cap input batches accordingly.
			List<Smelts.FuelComponent> components = plan.fuelPlan();
			if (components.isEmpty()) {
				outcome = new ExecutionFailure("evaluate returned Ok with empty fuelPlan");
				return outcome;
			}
			Smelts.FuelComponent first = components.get(0);
			FuelValues fv = mc.level.fuelValues();
			Item firstFuelItem = BuiltInRegistries.ITEM.getValue(first.id());
			int firstBurnPerPiece = fv.burnDuration(new ItemStack(firstFuelItem));
			if (firstBurnPerPiece <= 0) {
				outcome = new ExecutionFailure("fuel '" + first.id() + "' has zero burn duration");
				return outcome;
			}
			long firstTotalBurnTicks = (long) first.pieces() * firstBurnPerPiece;
			int firstCanCook = (int) Math.min(Integer.MAX_VALUE, firstTotalBurnTicks / plan.cookTicks());
			int finalBatches = Math.min(plan.batches(), firstCanCook);
			if (finalBatches <= 0) {
				outcome = new ExecutionFailure(
						"fuel-capped to 0 smelts; first fuel component covers no full smelt");
				return outcome;
			}
			int finalOutputCount = finalBatches * plan.outputPerBatch();

			touched[0] = true;

			// Evict pre-existing result so the cook starts with a clean output slot.
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

			Item inputItem = BuiltInRegistries.ITEM.getValue(plan.inputItem());

			// Evict mismatched input.
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
			// Evict mismatched fuel.
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

			// Push only the batches we can actually cook. Pre-existing matching input counts toward this.
			int alreadyInFurnaceInput = (peek.input().getItem() == inputItem) ? peek.input().getCount() : 0;
			int inputToPush = Math.max(0, finalBatches - alreadyInFurnaceInput);
			if (inputToPush > 0) {
				ExecutionFailure pushInput = pushIntoSlot(mc, cid, inputItem,
						AbstractFurnaceMenu.INGREDIENT_SLOT, inputToPush);
				if (pushInput != null) { outcome = pushInput; return outcome; }
				Thread.sleep(CLICK_SETTLE_MS);
			}

			// Push the first fuel component.
			if (first.toPush() > 0) {
				ExecutionFailure pf = pushIntoSlot(mc, cid, firstFuelItem,
						AbstractFurnaceMenu.FUEL_SLOT, first.toPush());
				if (pf != null) { outcome = pf; return outcome; }
				Thread.sleep(CLICK_SETTLE_MS);
			}

			Thread.sleep(FINAL_SETTLE_MS);

			long startedAtMs = System.currentTimeMillis();
			FurnaceRegistry.FuelLoaded fuelEntry = new FurnaceRegistry.FuelLoaded(
					first.id(), first.pieces(), firstBurnPerPiece);

			outcome = new IgniteOk(
					pre.furnacePos(),
					plan.inputItem(),
					finalBatches,
					plan.resultItem(),
					finalOutputCount,
					List.of(fuelEntry),
					plan.cookTicks(),
					startedAtMs,
					plan.batches());
			return outcome;
		} finally {
			final boolean wasTouched = touched[0];
			final IgniteResult finalOutcome = outcome;
			supply(() -> {
				LocalPlayer p = mc.player;
				if (p != null && p.containerMenu != p.inventoryMenu
						&& p.containerMenu.containerId == containerId) {
					// On validation-only failure, leave the user's pre-loaded items where they were.
					// We never evict on ignite-success — we WANT the input/fuel left in the furnace
					// so the cook can proceed after we close the menu.
					if (wasTouched && !(finalOutcome instanceof IgniteOk)) {
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

	// ── collect ────────────────────────────────────────────────────────────

	public sealed interface CollectResult permits CollectOk, CollectFailure {}

	public record CollectOk(
			ResourceLocation collectedItem,
			int collectedCount,
			int inputRemaining,
			int fuelRemainingBurns
	) implements CollectResult {}

	public record CollectFailure(String reason, String message) implements CollectResult {}

	/**
	 * Open the furnace at {@code pos}, pull whatever's in the result slot into inventory, peek
	 * input + fuel for the registry, close. Caller is expected to be within reach already.
	 */
	public static CollectResult collectFromFurnace(BlockPos pos) throws InterruptedException {
		Minecraft mc = Minecraft.getInstance();

		CollectFailure preFail = supply(() -> {
			LocalPlayer p = mc.player;
			if (p == null) return new CollectFailure("internal_error", "no player (not in world)");
			if (mc.gameMode == null) return new CollectFailure("internal_error", "no gameMode");
			if (mc.level == null) return new CollectFailure("internal_error", "no level");
			if (p.containerMenu != p.inventoryMenu) {
				return new CollectFailure("internal_error",
						"another inventory screen is open; close it before collecting");
			}
			return null;
		});
		if (preFail != null) return preFail;

		ExecutionFailure openFail = supply(() -> openFurnace(mc, pos));
		if (openFail != null) return new CollectFailure("internal_error", openFail.message);

		int containerId = waitForFurnaceMenu(mc, MENU_OPEN_TIMEOUT_MS);
		if (containerId < 0) {
			supply(() -> { closeContainerSafely(mc); return null; });
			return new CollectFailure("internal_error",
					"furnace at " + pos + " did not open a FurnaceMenu within " + MENU_OPEN_TIMEOUT_MS + "ms");
		}

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
				return new CollectFailure("internal_error", "furnace menu vanished before peek");
			}

			ItemStack resultStack = peek.result();
			ResourceLocation collectedItem = null;
			int collectedCount = 0;
			if (!resultStack.isEmpty()) {
				Item resultItem = resultStack.getItem();
				collectedItem = BuiltInRegistries.ITEM.getKey(resultItem);
				int beforeOutput = supply(() -> countItem(mc.player, resultItem));
				supply(() -> {
					LocalPlayer p = mc.player;
					if (p != null && p.containerMenu.containerId == cid) {
						mc.gameMode.handleInventoryMouseClick(cid,
								AbstractFurnaceMenu.RESULT_SLOT, 0, ClickType.QUICK_MOVE, p);
					}
					return null;
				});
				Thread.sleep(FINAL_SETTLE_MS);
				int afterOutput = supply(() -> countItem(mc.player, resultItem));
				collectedCount = Math.max(0, afterOutput - beforeOutput);
			}

			int inputRemaining = peek.input().getCount();
			ItemStack fuelStack = peek.fuel();
			int fuelRemainingBurns = 0;
			if (!fuelStack.isEmpty()) {
				FuelValues fv = mc.level.fuelValues();
				int burnPer = fv.burnDuration(fuelStack);
				if (burnPer > 0) {
					// Approximate; "burns" here is in cook-batch units (200 ticks each).
					fuelRemainingBurns = (int) ((long) fuelStack.getCount() * burnPer / 200L);
				}
			}

			return new CollectOk(collectedItem, collectedCount, inputRemaining, fuelRemainingBurns);
		} finally {
			supply(() -> {
				LocalPlayer p = mc.player;
				if (p != null && p.containerMenu != p.inventoryMenu
						&& p.containerMenu.containerId == containerId) {
					closeContainerSafely(mc);
				}
				return null;
			});
		}
	}

	// ── helpers ────────────────────────────────────────────────────────────

	private record SlotPeek(ItemStack input, ItemStack fuel, ItemStack result) {}

	private static ExecutionFailure pushIntoSlot(Minecraft mc, int containerId, Item item,
												  int targetSlot, int needed) throws InterruptedException {
		final int FURNACE_SLOTS = AbstractFurnaceMenu.SLOT_COUNT;
		final int INV_END = FURNACE_SLOTS + 36;
		final int MAX_ATTEMPTS = 16;

		// Move EXACTLY `needed` of `item` into targetSlot. We deliberately avoid
		// ClickType.QUICK_MOVE (shift-click): it moves the WHOLE source stack
		// regardless of `needed`, over-loading the furnace and stranding the
		// unmoved remainder inside it — observed as a 2-item cook shift-clicking
		// all 8 coal into the fuel slot, leaving inventory empty (no_fuel) for the
		// next cook. Instead, per attempt: pick a source stack onto the cursor,
		// right-click `needed` singles into the target, and left-click the
		// remainder back into the source slot. Multiple source stacks are drained
		// across attempts until `needed` is placed.
		int remaining = needed;
		for (int attempt = 0; attempt < MAX_ATTEMPTS && remaining > 0; attempt++) {
			final int want = remaining;
			Integer placed = supply(() -> {
				LocalPlayer p = mc.player;
				if (p == null) return null;
				AbstractContainerMenu menu = p.containerMenu;
				if (menu.containerId != containerId) return null;

				int sourceSlot = -1;
				for (int s = FURNACE_SLOTS; s < INV_END; s++) {
					ItemStack stk = menu.getSlot(s).getItem();
					if (!stk.isEmpty() && stk.getItem() == item) { sourceSlot = s; break; }
				}
				if (sourceSlot < 0) return 0;  // nothing left in inventory to move

				// Pick up the source stack, deposit `want` singles into the target,
				// then return whatever's left on the cursor to the source slot.
				mc.gameMode.handleInventoryMouseClick(containerId, sourceSlot, 0, ClickType.PICKUP, p);
				int toPlace = Math.min(want, menu.getCarried().getCount());
				for (int i = 0; i < toPlace; i++) {
					mc.gameMode.handleInventoryMouseClick(containerId, targetSlot, 1, ClickType.PICKUP, p);
				}
				if (!menu.getCarried().isEmpty()) {
					mc.gameMode.handleInventoryMouseClick(containerId, sourceSlot, 0, ClickType.PICKUP, p);
				}
				return toPlace;
			});

			if (placed == null) {
				return new ExecutionFailure("player or furnace menu vanished while pushing "
						+ BuiltInRegistries.ITEM.getKey(item));
			}
			if (placed == 0) {
				return new ExecutionFailure("ran out of " + BuiltInRegistries.ITEM.getKey(item)
						+ " in inventory before " + needed + " were transferred to slot " + targetSlot);
			}
			remaining -= placed;
			if (remaining > 0) Thread.sleep(CLICK_SETTLE_MS);
		}
		if (remaining > 0) {
			return new ExecutionFailure("could not push " + needed + " " + BuiltInRegistries.ITEM.getKey(item)
					+ " into slot " + targetSlot + " after " + MAX_ATTEMPTS + " attempts");
		}
		return null;
	}

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
