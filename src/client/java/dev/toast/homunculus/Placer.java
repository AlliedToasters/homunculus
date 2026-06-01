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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Auto-places a single block near the player's feet. The agent is effectively blind to look
 * direction, so positional reasoning lives in the mod: we pick a candidate spot and rotate the
 * player toward it for the placement packet. The rotation is left in place — the spectator camera
 * stays on the placed block instead of snapping back to whatever the agent was last facing.
 *
 * Anti-encasement: by default ({@link #ESCAPE_CHECK}) a placement is rejected only if it would
 * leave the player with zero stepable ring-1 exits, validated per-candidate ({@link #leavesEscape}).
 * This admits tunnel placements (a 1-wide corridor keeps forward/back open) while still refusing to
 * seal a true 1-tile pocket. The legacy blanket gate — at least {@code RING_1_OPEN_MIN} of the 8
 * ring-1 tiles open (default 3, env {@code HOMUNCULUS_RING1_OPEN_MIN}) — is restored by setting
 * {@code HOMUNCULUS_PLACE_ESCAPE_CHECK=0}. Candidate search prefers ring 2 over ring 1 to give the
 * placed block breathing room away from the player.
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

	/**
	 * Anti-encasement mode. The legacy {@link #RING_1_OPEN_MIN} gate is a blanket
	 * pre-search check on ring-1 openness — fast, but it mis-fires underground: a
	 * 1-wide × 2-tall tunnel has only ~2/8 ring tiles open (forward + back), so the
	 * gate trips {@code no_space} <em>before the candidate search runs</em>, even
	 * though placing a block in the corridor ahead never walls the player in. That
	 * false-positive was the #2 wall-clock sink in the goal=diamond waves.
	 *
	 * <p>Escape-check (default on; {@code HOMUNCULUS_PLACE_ESCAPE_CHECK=0} restores
	 * the blanket gate for A/B) drops the pre-search return and instead validates
	 * each candidate: a placement is rejected only if it would leave the player with
	 * <em>zero</em> stepable ring-1 exits (see {@link #leavesEscape}). Tunnel
	 * placements (forward/back stay open) pass; a true 1-tile pocket (placing the
	 * cell seals the last exit) is still rejected.
	 */
	private static final boolean ESCAPE_CHECK = readEscapeCheck();

	private static boolean readEscapeCheck() {
		String raw = System.getenv("HOMUNCULUS_PLACE_ESCAPE_CHECK");
		if (raw == null || raw.isBlank()) return true;
		String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
		return !(v.equals("0") || v.equals("false") || v.equals("no"));
	}

	/**
	 * Make-room (Tier 2): when no ready candidate exists at any of {@code feet.y±1},
	 * pick a candidate whose floor is sturdy but whose cell holds a clearable block,
	 * Excavate that single cell, and retry. Default on; kill via
	 * {@code HOMUNCULUS_PLACE_MAKE_ROOM=0} for A/B. Isolated to {@link #place} —
	 * {@link #placeAt} (shelter/doorway path) never clears, to avoid griefing walls.
	 */
	private static final boolean MAKE_ROOM = readMakeRoom();
	private static final long MAKE_ROOM_TIMEOUT_SECONDS = 20;

	private static boolean readMakeRoom() {
		String raw = System.getenv("HOMUNCULUS_PLACE_MAKE_ROOM");
		if (raw == null || raw.isBlank()) return true;
		String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
		return !(v.equals("0") || v.equals("false") || v.equals("no"));
	}

	/**
	 * Clearable-block allowlist for make-room. Fail-safe: only these solid blocks
	 * are broken to free a placement cell. Leaves are matched by class (all species).
	 * Replaceable vegetation (tall grass, ferns, snow layer) is NOT here — those are
	 * already place-over candidates via {@link #isOpenForPlacement}. Excluded by
	 * omission: logs (no griefing wood), ores, *any* block with a BlockEntity
	 * (chests/furnaces never appear in this set), bedrock, obsidian, supports.
	 */
	private static final Set<Block> CLEARABLE_TERRAIN = Set.of(
		Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.COARSE_DIRT,
		Blocks.ROOTED_DIRT, Blocks.DIRT_PATH, Blocks.MUD, Blocks.CLAY,
		Blocks.SAND, Blocks.RED_SAND, Blocks.GRAVEL,
		Blocks.STONE, Blocks.COBBLESTONE, Blocks.ANDESITE, Blocks.DIORITE,
		Blocks.GRANITE, Blocks.TUFF, Blocks.DEEPSLATE,
		Blocks.SNOW_BLOCK, Blocks.MOSS_BLOCK, Blocks.CALCITE
	);

	private static boolean isClearable(Block b) {
		return b instanceof LeavesBlock || CLEARABLE_TERRAIN.contains(b);
	}

	/** y offsets searched, in preference order: feet level, one step down, one step up. */
	private static final int[] Y_OFFSETS = { 0, -1, 1 };

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

		// Make-room: the search found no ready cell but a clearable one. Excavate
		// that single cell (Baritone-managed break: pathing/AutoTool/survival
		// timing + SESSION_LOCK, which /place does not hold) then re-run the
		// search — the freed cell now qualifies as a Tier-1 spot.
		if (outcome.clearTarget != null) {
			BlockPos c = outcome.clearTarget;
			String clearedBlockId = supply(() ->
					BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(c).getBlock()).toString());
			Excavate.Outcome ex = Excavate.run(
					c.getX(), c.getY(), c.getZ(), c.getX(), c.getY(), c.getZ(),
					MAKE_ROOM_TIMEOUT_SECONDS);
			if (!(ex instanceof Excavate.Cleared)) {
				return new Failure("make_room_failed",
						"tried to clear " + clearedBlockId + " at " + c + " to make room but excavate "
								+ (ex instanceof Excavate.Failed f ? f.reason() + " (" + f.message() + ")" : "did not complete"));
			}
			HomunculusClient.LOGGER.info("[place make-room] cleared {} at {} for '{}'", clearedBlockId, c, itemIdStr);
			outcome = supply(() -> searchAndPrecheck(mc, item, itemIdStr));
			if (outcome.failure != null) return outcome.failure;
			if (outcome.target == null) {
				return new Failure("no_placeable_spot",
						"cleared a cell at " + c + " but still no placeable spot — relocate and retry");
			}
		}

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

	private record SearchOutcome(BlockPos target, BlockPos clearTarget, Failure failure) {
		static SearchOutcome ok(BlockPos t) { return new SearchOutcome(t, null, null); }
		static SearchOutcome clear(BlockPos c) { return new SearchOutcome(null, c, null); }
		static SearchOutcome fail(Failure f) { return new SearchOutcome(null, null, f); }
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

		// Legacy blanket anti-encasement gate — only when escape-check is off.
		// With escape-check on (default) the per-candidate leavesEscape test below
		// replaces it, so tunnels are no longer rejected pre-search.
		if (!ESCAPE_CHECK) {
			int openCount = 0;
			for (int[] off : RING_1) {
				if (isOpenForPlacement(level, feet.offset(off[0], 0, off[1]))) openCount++;
			}
			if (openCount < RING_1_OPEN_MIN) {
				return SearchOutcome.fail(new Failure("no_space",
						"need more space around player; only " + openCount + "/8 adjacent tiles clear (need "
								+ RING_1_OPEN_MIN + "+) — relocate to open ground"));
			}
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
		// Tier 1: a ready candidate is an open cell with a sturdy floor below.
		// Scan all same-level candidates first (flat ground ideal), then one step
		// down (terrace), then one step up — this recovers slope spawns where the
		// feet-Y neighbours are into-the-hill or over-the-edge.
		boolean sawDoorAdjacent = false;
		boolean sawSealing = false;
		for (int dy : Y_OFFSETS) {
			for (int[] off : SEARCH_ORDER) {
				BlockPos cand = feet.offset(off[0], dy, off[1]);
				if (!isOpenForPlacement(level, cand)) continue;
				if (blocksDoorway(level, cand)) {
					sawDoorAdjacent = true;
					continue;
				}
				BlockPos below = cand.below();
				BlockState supportState = level.getBlockState(below);
				if (!supportState.isFaceSturdy(level, below, Direction.UP)) continue;
				if (ESCAPE_CHECK && !leavesEscape(level, feet, cand)) {
					sawSealing = true;
					continue;
				}
				return SearchOutcome.ok(cand);
			}
		}

		// Tier 2 (make-room): no ready cell. Pick a candidate that is currently
		// occupied by a clearable block but sits on a sturdy floor — Excavating
		// that one cell turns it into a Tier-1 spot. Caller runs the dig + retries.
		if (MAKE_ROOM) {
			for (int dy : Y_OFFSETS) {
				for (int[] off : SEARCH_ORDER) {
					BlockPos cand = feet.offset(off[0], dy, off[1]);
					if (isOpenForPlacement(level, cand)) continue; // Tier-1 territory
					if (blocksDoorway(level, cand)) continue;
					if (!isClearable(level.getBlockState(cand).getBlock())) continue;
					BlockPos below = cand.below();
					BlockState supportState = level.getBlockState(below);
					if (!supportState.isFaceSturdy(level, below, Direction.UP)) continue;
					if (hasFluidNeighbor(level, cand)) continue;
					if (ESCAPE_CHECK && !leavesEscape(level, feet, cand)) {
						sawSealing = true;
						continue;
					}
					return SearchOutcome.clear(cand);
				}
			}
		}

		if (sawDoorAdjacent) {
			return SearchOutcome.fail(new Failure("blocks_doorway",
					"every candidate placement would block a nearby door/gate — step away (travel 2-3 blocks) before placing"));
		}
		if (sawSealing) {
			return SearchOutcome.fail(new Failure("no_space",
					"every reachable placement would seal you in (no ring-1 escape tile left) — step to open ground before placing"));
		}
		return SearchOutcome.fail(new Failure("no_placeable_spot",
				"no flat ground within 2 blocks of player (open above, sturdy below) — relocate and retry"));
	}

	/**
	 * Escape-check: would placing at {@code cand} leave the player at least one
	 * stepable ring-1 exit? An exit is a feet-level cardinal/diagonal cell whose
	 * foot AND head cells are open for placement (the player is 2 tall). A
	 * candidate "consumes" an exit only when it lands in that exit's foot cell
	 * (dy=0) or head cell (dy=+1); a candidate below the exit (dy=-1) or out at
	 * ring 2 leaves every feet-level exit untouched. True ⇒ placement is safe.
	 */
	private static boolean leavesEscape(ClientLevel level, BlockPos feet, BlockPos cand) {
		for (int[] off : RING_1) {
			BlockPos foot = feet.offset(off[0], 0, off[1]);
			BlockPos head = foot.above();
			if (foot.equals(cand) || head.equals(cand)) continue; // placement seals this exit
			if (isOpenForPlacement(level, foot) && isOpenForPlacement(level, head)) {
				return true;
			}
		}
		return false;
	}

	/** True if any of the 6 face-neighbours of {@code pos} holds a fluid (lava/water/waterlogged). */
	private static boolean hasFluidNeighbor(ClientLevel level, BlockPos pos) {
		for (Direction d : Direction.values()) {
			if (!level.getBlockState(pos.relative(d)).getFluidState().isEmpty()) return true;
		}
		return false;
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
