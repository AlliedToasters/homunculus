package dev.toast.homunculus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Auto-places a single block near the player's feet. The agent is effectively blind to look
 * direction, so positional reasoning lives in the mod: we pick a candidate spot and rotate the
 * player toward it for the placement packet. The rotation is left in place — the spectator camera
 * stays on the placed block instead of snapping back to whatever the agent was last facing.
 *
 * Anti-casing precondition: at least {@code RING_1_OPEN_MIN} of the 8 ring-1 tiles around the
 * player's feet must be open (air or replaceable). Default 3, override via env var
 * {@code HOMUNCULUS_RING1_OPEN_MIN} (read at static init). If fewer, return {@code no_space} —
 * placing in a tight pocket walls the agent in. Candidate search prefers ring 2 over ring 1 to
 * give the placed block breathing room away from the player.
 *
 * Sneak is held through the call so a usable support block (e.g. a chest) doesn't pop its GUI.
 */
public final class Placer {
	private Placer() {}

	private static final long PER_OP_TIMEOUT_MS = 2000;
	private static final long SELECT_SETTLE_MS = 100;
	private static final long ROT_SETTLE_MS = 50;
	private static final long SNEAK_SETTLE_MS = 100;
	private static final long PLACE_SETTLE_MS = 150;

	private static final int RING_1_OPEN_MIN = readRing1OpenMin();

	private static int readRing1OpenMin() {
		String raw = System.getenv("HOMUNCULUS_RING1_OPEN_MIN");
		if (raw == null || raw.isBlank()) return 3;
		try {
			int v = Integer.parseInt(raw.trim());
			if (v < 0) v = 0;
			if (v > 8) v = 8;
			return v;
		} catch (NumberFormatException e) {
			return 3;
		}
	}

	/** 8 tiles at Chebyshev distance 1 from the player's feet, NESW first then diagonals. */
	private static final int[][] RING_1 = {
		{ 0, -1}, { 1,  0}, { 0,  1}, {-1,  0},
		{ 1, -1}, { 1,  1}, {-1,  1}, {-1, -1},
	};

	/**
	 * Candidate scan order. Ring 2 (distance 2) is preferred over ring 1 — placing a block 2 tiles
	 * away gives the player breathing room. Within each ring, orthogonal (cardinal) directions are
	 * tried first, then edges flanking each cardinal, then corners.
	 */
	private static final int[][] SEARCH_ORDER = {
		// ring 2 — cardinals
		{ 0, -2}, { 2,  0}, { 0,  2}, {-2,  0},
		// ring 2 — edges flanking each cardinal
		{ 1, -2}, {-1, -2},
		{ 2, -1}, { 2,  1},
		{ 1,  2}, {-1,  2},
		{-2, -1}, {-2,  1},
		// ring 2 — corners
		{ 2, -2}, { 2,  2}, {-2,  2}, {-2, -2},
		// ring 1 — cardinals
		{ 0, -1}, { 1,  0}, { 0,  1}, {-1,  0},
		// ring 1 — diagonals
		{ 1, -1}, { 1,  1}, {-1,  1}, {-1, -1},
	};

	public sealed interface Result permits Ok, Failure {}
	public record Ok(int x, int y, int z) implements Result {}
	public record Failure(String reason, String message) implements Result {}

	public static Result place(String itemIdStr) throws InterruptedException {
		ResourceLocation itemId = ResourceLocation.tryParse(itemIdStr);
		if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) {
			return new Failure("not_placeable", "no item with id '" + itemIdStr + "'");
		}
		Item item = BuiltInRegistries.ITEM.getValue(itemId);
		if (!(item instanceof BlockItem blockItem)) {
			return new Failure("not_placeable", "item '" + itemIdStr + "' is not a block");
		}
		Block expectedBlock = blockItem.getBlock();
		Minecraft mc = Minecraft.getInstance();

		SearchOutcome outcome = supply(() -> searchAndPrecheck(mc, item, itemIdStr));
		if (outcome.failure != null) return outcome.failure;
		BlockPos target = outcome.target;
		BlockPos support = target.below();

		Failure selectFailure = supply(() -> selectForPlacement(mc, item, itemIdStr));
		if (selectFailure != null) return selectFailure;
		Thread.sleep(SELECT_SETTLE_MS);

		Boolean rotated = supply(() -> { Look.faceBlockTop(mc, support); return mc.player != null; });
		if (rotated == null || !rotated) return new Failure("internal_error", "player vanished during rotate");
		Thread.sleep(ROT_SETTLE_MS);

		Boolean priorShift = supply(() -> beginSneak(mc));
		if (priorShift == null) {
			return new Failure("internal_error", "player vanished after rotate");
		}

		try {
			Thread.sleep(SNEAK_SETTLE_MS);
			supply(() -> { sendPlacePacket(mc, support); return null; });
			Thread.sleep(PLACE_SETTLE_MS);
			return supply(() -> verifyPlacement(mc, target, expectedBlock, itemIdStr));
		} finally {
			final boolean restore = priorShift;
			supply(() -> { endSneak(mc, restore); return null; });
		}
	}

	/**
	 * Place a block at an explicit target coordinate (caller-chosen, no candidate search).
	 *
	 * <p>The agent stands wherever it stands; we don't move it. We only verify:
	 * <ul>
	 *   <li>item exists, is a BlockItem, and is in inventory
	 *   <li>target cell is air/replaceable (placement won't overwrite something)
	 *   <li>support block (target.below()) is sturdy on its top face
	 *   <li>support top is within ~5.5 blocks of the player's eyes (MC reach)
	 * </ul>
	 *
	 * <p>Skips the ring-1 open-tiles "anti-casing" check that {@link #place} uses —
	 * the agent is choosing where to place, so blocking themselves in is their
	 * problem, not ours.
	 *
	 * <p>Doors and other multi-block items: only the bottom coord is specified;
	 * MC's BlockItem.useOn auto-handles the upper half. If the cell above target
	 * is not air, the place will fail at the verify step.
	 */
	public static Result placeAt(String itemIdStr, BlockPos target) throws InterruptedException {
		ResourceLocation itemId = ResourceLocation.tryParse(itemIdStr);
		if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) {
			return new Failure("not_placeable", "no item with id '" + itemIdStr + "'");
		}
		Item item = BuiltInRegistries.ITEM.getValue(itemId);
		if (!(item instanceof BlockItem blockItem)) {
			return new Failure("not_placeable", "item '" + itemIdStr + "' is not a block");
		}
		Block expectedBlock = blockItem.getBlock();
		Minecraft mc = Minecraft.getInstance();
		BlockPos support = target.below();

		Failure preFailure = supply(() -> precheckAt(mc, item, itemIdStr, target, support));
		if (preFailure != null) return preFailure;

		Failure selectFailure = supply(() -> selectForPlacement(mc, item, itemIdStr));
		if (selectFailure != null) return selectFailure;
		Thread.sleep(SELECT_SETTLE_MS);

		Boolean rotated = supply(() -> { Look.faceBlockTop(mc, support); return mc.player != null; });
		if (rotated == null || !rotated) return new Failure("internal_error", "player vanished during rotate");
		Thread.sleep(ROT_SETTLE_MS);

		Boolean priorShift = supply(() -> beginSneak(mc));
		if (priorShift == null) {
			return new Failure("internal_error", "player vanished after rotate");
		}

		try {
			Thread.sleep(SNEAK_SETTLE_MS);
			supply(() -> { sendPlacePacket(mc, support); return null; });
			Thread.sleep(PLACE_SETTLE_MS);
			return supply(() -> verifyPlacement(mc, target, expectedBlock, itemIdStr));
		} finally {
			final boolean restore = priorShift;
			supply(() -> { endSneak(mc, restore); return null; });
		}
	}

	private static Failure precheckAt(Minecraft mc, Item item, String itemIdStr, BlockPos target, BlockPos support) {
		LocalPlayer p = mc.player;
		if (p == null) return new Failure("internal_error", "no player (not in world)");
		if (mc.gameMode == null) return new Failure("internal_error", "no gameMode");
		ClientLevel level = mc.level;
		if (level == null) return new Failure("internal_error", "no level");
		if (p.containerMenu != p.inventoryMenu) {
			return new Failure("internal_error",
					"another inventory screen is open; close it before placing");
		}
		if (scan(p.getInventory().items, item) < 0) {
			return new Failure("not_in_inventory", "no '" + itemIdStr + "' in inventory");
		}
		if (!isOpenForPlacement(level, target)) {
			BlockState s = level.getBlockState(target);
			return new Failure("target_blocked",
					"target " + target + " is " + BuiltInRegistries.BLOCK.getKey(s.getBlock())
							+ " (need air/replaceable)");
		}
		BlockState supportState = level.getBlockState(support);
		if (!supportState.isFaceSturdy(level, support, Direction.UP)) {
			return new Failure("no_sturdy_support",
					"block below target (" + support + ") top face not sturdy — was "
							+ BuiltInRegistries.BLOCK.getKey(supportState.getBlock()));
		}
		Vec3 supportTop = new Vec3(support.getX() + 0.5, support.getY() + 1.0, support.getZ() + 0.5);
		double dist = p.getEyePosition().distanceTo(supportTop);
		if (dist > 5.5) {
			return new Failure("out_of_reach",
					"support top is " + String.format(java.util.Locale.ROOT, "%.2f", dist)
							+ " blocks from player eyes (max ~5.5)");
		}
		return null;
	}

	private record SearchOutcome(BlockPos target, Failure failure) {
		static SearchOutcome ok(BlockPos t) { return new SearchOutcome(t, null); }
		static SearchOutcome fail(Failure f) { return new SearchOutcome(null, f); }
	}

	private static SearchOutcome searchAndPrecheck(Minecraft mc, Item item, String itemIdStr) {
		LocalPlayer p = mc.player;
		if (p == null) return SearchOutcome.fail(new Failure("internal_error", "no player (not in world)"));
		if (mc.gameMode == null) return SearchOutcome.fail(new Failure("internal_error", "no gameMode"));
		ClientLevel level = mc.level;
		if (level == null) return SearchOutcome.fail(new Failure("internal_error", "no level"));
		if (p.containerMenu != p.inventoryMenu) {
			return SearchOutcome.fail(new Failure("internal_error",
					"another inventory screen is open; close it before placing"));
		}
		if (scan(p.getInventory().items, item) < 0) {
			return SearchOutcome.fail(new Failure("not_in_inventory", "no '" + itemIdStr + "' in inventory"));
		}

		BlockPos feet = p.blockPosition();

		int openCount = 0;
		for (int[] off : RING_1) {
			if (isOpenForPlacement(level, feet.offset(off[0], 0, off[1]))) openCount++;
		}
		if (openCount < RING_1_OPEN_MIN) {
			return SearchOutcome.fail(new Failure("no_space",
					"need more space around player; only " + openCount + "/8 adjacent tiles clear (need "
							+ RING_1_OPEN_MIN + "+) — relocate to open ground"));
		}

		// Doorway guard. Per-candidate: reject if `cand` is itself a door/
		// gate, or has a door/gate as a cardinal neighbor (checked at the
		// candidate's y and ±1 y to cover door upper halves + elevated
		// supports). Cardinal-only because doors are flat — a diagonal
		// block beside a door does not wall off the 2-tall passage.
		//
		// Per-candidate replaces the prior "scan box around feet" approach
		// which missed doors at the candidate's far side (ring-2 candidates
		// at offset ±2 can sit 1 cell from a door at offset ±3, which the
		// old ±2 scan box never saw — shelter doorway repro).
		boolean sawDoorAdjacent = false;
		for (int[] off : SEARCH_ORDER) {
			BlockPos cand = feet.offset(off[0], 0, off[1]);
			if (!isOpenForPlacement(level, cand)) continue;
			if (blocksDoorway(level, cand)) {
				sawDoorAdjacent = true;
				continue;
			}
			BlockPos below = cand.below();
			BlockState supportState = level.getBlockState(below);
			if (supportState.isFaceSturdy(level, below, Direction.UP)) {
				return SearchOutcome.ok(cand);
			}
		}
		if (sawDoorAdjacent) {
			return SearchOutcome.fail(new Failure("blocks_doorway",
					"every candidate placement would block a nearby door/gate — step away (travel 2-3 blocks) before placing"));
		}
		return SearchOutcome.fail(new Failure("no_placeable_spot",
				"no flat ground within 2 blocks of player (open above, sturdy below) — relocate and retry"));
	}

	private static boolean blocksDoorway(ClientLevel level, BlockPos cand) {
		for (int dy = -1; dy <= 1; dy++) {
			BlockPos at = cand.offset(0, dy, 0);
			if (isDoorOrGate(level, at)) return true;
			if (isDoorOrGate(level, at.north())) return true;
			if (isDoorOrGate(level, at.south())) return true;
			if (isDoorOrGate(level, at.east())) return true;
			if (isDoorOrGate(level, at.west())) return true;
		}
		return false;
	}

	private static boolean isDoorOrGate(ClientLevel level, BlockPos pos) {
		Block b = level.getBlockState(pos).getBlock();
		return b instanceof DoorBlock || b instanceof FenceGateBlock;
	}

	private static boolean isOpenForPlacement(ClientLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.isAir() || state.canBeReplaced();
	}

	private static Failure selectForPlacement(Minecraft mc, Item item, String itemIdStr) {
		LocalPlayer p = mc.player;
		if (p == null) return new Failure("internal_error", "no player (not in world)");
		Inventory inv = p.getInventory();
		int invSlot = scan(inv.items, item);
		if (invSlot < 0) {
			return new Failure("not_in_inventory", "no '" + itemIdStr + "' in inventory");
		}
		if (Inventory.isHotbarSlot(invSlot)) {
			inv.selected = invSlot;
		} else {
			// Main inventory slot 9..35 maps 1:1 to InventoryMenu slot index. SWAP with the
			// currently-held hotbar slot pulls the item into the held position.
			mc.gameMode.handleInventoryMouseClick(
					InventoryMenu.CONTAINER_ID, invSlot, inv.selected,
					ClickType.SWAP, p);
		}
		return null;
	}

	private static Boolean beginSneak(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null) return null;
		boolean prior = p.isShiftKeyDown();
		p.setShiftKeyDown(true);
		p.connection.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY));
		return prior;
	}

	private static void endSneak(Minecraft mc, boolean restoreTo) {
		LocalPlayer p = mc.player;
		if (p == null) return;
		if (!restoreTo) {
			p.connection.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
		}
		p.setShiftKeyDown(restoreTo);
	}

	private static void sendPlacePacket(Minecraft mc, BlockPos support) {
		LocalPlayer p = mc.player;
		if (p == null || mc.gameMode == null) return;
		Vec3 hitLoc = new Vec3(support.getX() + 0.5, support.getY() + 1.0, support.getZ() + 0.5);
		BlockHitResult bhit = new BlockHitResult(hitLoc, Direction.UP, support, false);
		InteractionResult ir = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, bhit);
		if (ir.consumesAction()) p.swing(InteractionHand.MAIN_HAND);
	}

	private static Result verifyPlacement(Minecraft mc, BlockPos target, Block expectedBlock, String itemIdStr) {
		if (mc.level == null) return new Failure("internal_error", "level vanished mid-op");
		if (mc.level.getBlockState(target).getBlock() == expectedBlock) {
			return new Ok(target.getX(), target.getY(), target.getZ());
		}
		return new Failure("internal_error",
				"placement did not take — expected '" + itemIdStr + "' at " + target
						+ " but block did not change (server rejected, or terrain shifted)");
	}

	private static int scan(NonNullList<ItemStack> stacks, Item item) {
		for (int i = 0; i < stacks.size(); i++) {
			ItemStack s = stacks.get(i);
			if (!s.isEmpty() && s.getItem() == item) return i;
		}
		return -1;
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
}
