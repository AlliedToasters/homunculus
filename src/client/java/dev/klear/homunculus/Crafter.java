package dev.klear.homunculus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes a craft as a sequence of recipe-place + shift-click-output packets. Called from the HTTP
 * thread; hops to the client thread for each MC operation, with sleeps in between so the server
 * tick can apply slot updates and bump the menu stateId before the next click (modern container
 * sync rejects clicks with a stale stateId).
 *
 * 2x2 uses InventoryMenu (CONTAINER_ID 0). 3x3 right-clicks a nearby crafting_table to open a
 * server-assigned CraftingMenu, then runs the same packet rhythm against that menu's containerId,
 * and closes when done.
 */
public final class Crafter {
	private Crafter() {}

	private static final long PER_OP_TIMEOUT_MS = 2000;
	private static final long PLACE_SETTLE_MS = 100;
	private static final long CLICK_SETTLE_MS = 50;
	private static final long FINAL_SETTLE_MS = 150;
	private static final long MENU_OPEN_TIMEOUT_MS = 1500;
	private static final long MENU_POLL_INTERVAL_MS = 50;

	public sealed interface Result permits Ok, Failure {}
	public record Ok(String id, int count) implements Result {}
	public record Failure(String reason, String message, String id, int crafted) implements Result {}

	public static Result execute2x2(Recipes.Ok recipe) throws InterruptedException {
		Minecraft mc = Minecraft.getInstance();
		Item resultItem = BuiltInRegistries.ITEM.getValue(recipe.resultItem());

		Failure pre = supply(() -> {
			LocalPlayer p = mc.player;
			if (p == null) return new Failure("internal_error", "no player (not in world)", null, 0);
			if (mc.gameMode == null) return new Failure("internal_error", "no gameMode", null, 0);
			if (p.containerMenu != p.inventoryMenu) {
				return new Failure("internal_error",
						"another inventory screen is open; close it before crafting", null, 0);
			}
			return null;
		});
		if (pre != null) return pre;

		int beforeCount = supply(() -> countItem(mc.player, resultItem));
		int targetTotal = beforeCount + recipe.totalOutput();

		for (int i = 0; i < recipe.batches(); i++) {
			supply(() -> {
				MultiPlayerGameMode gm = mc.gameMode;
				gm.handlePlaceRecipe(InventoryMenu.CONTAINER_ID, recipe.displayId(), false);
				return null;
			});
			Thread.sleep(PLACE_SETTLE_MS);
			supply(() -> {
				MultiPlayerGameMode gm = mc.gameMode;
				gm.handleInventoryMouseClick(
						InventoryMenu.CONTAINER_ID,
						InventoryMenu.RESULT_SLOT,
						0,
						ClickType.QUICK_MOVE,
						mc.player);
				return null;
			});
			Thread.sleep(CLICK_SETTLE_MS);
		}

		Thread.sleep(FINAL_SETTLE_MS);
		int afterCount = supply(() -> countItem(mc.player, resultItem));

		int crafted = afterCount - beforeCount;
		if (crafted >= recipe.totalOutput()) {
			return new Ok(recipe.resultItem().toString(), recipe.totalOutput());
		}
		return new Failure("internal_error",
				"expected " + recipe.totalOutput() + " " + recipe.resultItem()
						+ " but got " + crafted + " (target " + targetTotal + ", inventory " + afterCount + ")",
				recipe.resultItem().toString(), Math.max(0, crafted));
	}

	public static Result execute3x3(Recipes.Ok recipe) throws InterruptedException {
		Minecraft mc = Minecraft.getInstance();
		Item resultItem = BuiltInRegistries.ITEM.getValue(recipe.resultItem());
		BlockPos tablePos = recipe.craftingTablePos();
		if (tablePos == null) {
			return new Failure("internal_error", "no crafting table position recorded", null, 0);
		}

		Failure pre = supply(() -> {
			LocalPlayer p = mc.player;
			if (p == null) return new Failure("internal_error", "no player (not in world)", null, 0);
			if (mc.gameMode == null) return new Failure("internal_error", "no gameMode", null, 0);
			if (mc.level == null) return new Failure("internal_error", "no level", null, 0);
			if (p.containerMenu != p.inventoryMenu) {
				return new Failure("internal_error",
						"another inventory screen is open; close it before crafting", null, 0);
			}
			return null;
		});
		if (pre != null) return pre;

		int beforeCount = supply(() -> countItem(mc.player, resultItem));

		Failure openFail = supply(() -> openCraftingTable(mc, tablePos));
		if (openFail != null) return openFail;

		int containerId = waitForCraftingMenu(mc, MENU_OPEN_TIMEOUT_MS);
		if (containerId < 0) {
			supply(() -> { closeContainerSafely(mc); return null; });
			return new Failure("internal_error",
					"crafting_table at " + tablePos + " did not open a CraftingMenu within "
							+ MENU_OPEN_TIMEOUT_MS + "ms",
					null, 0);
		}

		final int cid = containerId;
		try {
			for (int i = 0; i < recipe.batches(); i++) {
				supply(() -> {
					mc.gameMode.handlePlaceRecipe(cid, recipe.displayId(), false);
					return null;
				});
				Thread.sleep(PLACE_SETTLE_MS);
				supply(() -> {
					mc.gameMode.handleInventoryMouseClick(
							cid, CraftingMenu.RESULT_SLOT, 0, ClickType.QUICK_MOVE, mc.player);
					return null;
				});
				Thread.sleep(CLICK_SETTLE_MS);
			}
			Thread.sleep(FINAL_SETTLE_MS);
		} finally {
			supply(() -> { closeContainerSafely(mc); return null; });
		}

		int afterCount = supply(() -> countItem(mc.player, resultItem));
		int crafted = afterCount - beforeCount;
		if (crafted >= recipe.totalOutput()) {
			return new Ok(recipe.resultItem().toString(), recipe.totalOutput());
		}
		return new Failure("internal_error",
				"expected " + recipe.totalOutput() + " " + recipe.resultItem()
						+ " but got " + crafted + " (inventory " + afterCount + ")",
				recipe.resultItem().toString(), Math.max(0, crafted));
	}

	private static Failure openCraftingTable(Minecraft mc, BlockPos tablePos) {
		LocalPlayer p = mc.player;
		if (p == null || mc.gameMode == null) {
			return new Failure("internal_error", "player vanished before opening table", null, 0);
		}
		// Sneak would skip activation and try to place whatever's in hand. Make sure we're not.
		if (p.isShiftKeyDown()) {
			p.setShiftKeyDown(false);
			p.connection.send(new ServerboundPlayerCommandPacket(
					p, ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
		}
		Look.faceBlockTop(mc, tablePos);
		Vec3 hitLoc = new Vec3(tablePos.getX() + 0.5, tablePos.getY() + 1.0, tablePos.getZ() + 0.5);
		BlockHitResult bhit = new BlockHitResult(hitLoc, Direction.UP, tablePos, false);
		mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, bhit);
		return null;
	}

	private static int waitForCraftingMenu(Minecraft mc, long timeoutMs) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			Integer id = supply(() -> {
				LocalPlayer p = mc.player;
				if (p == null) return null;
				AbstractContainerMenu menu = p.containerMenu;
				return (menu instanceof CraftingMenu) ? Integer.valueOf(menu.containerId) : null;
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
