package dev.toast.homunculus;

import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FuelValues;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Smelting recipe lookup, fuel selection, and furnace proximity. Cooking recipes are pushed to the
 * client as {@link FurnaceRecipeDisplay} entries in the same {@link ClientRecipeBook} used for
 * crafting (alongside Shaped/Shapeless display types). Same unlock-gating caveat: only recipes the
 * player has unlocked are visible.
 *
 * Two-phase API:
 *  - {@link #preCheck} runs the cheap checks (parse, recipe lookup, furnace proximity, count cap).
 *    It does NOT touch inventory or pick fuel — those depend on furnace contents which the client
 *    can only see after opening the menu (vanilla furnace BlockEntity items don't sync to clients).
 *  - {@link #evaluate} is called by Smelter once the menu is open and pre-loaded slot contents are
 *    known. It runs the inventory + fuel budget, factoring {@link ExtraStock} (matching items
 *    already in the furnace) into the available pool.
 *
 * Both phases must be invoked on the client thread.
 */
public final class Smelts {
	private Smelts() {}

	private static final int FURNACE_REACH = 4;
	private static final int FURNACE_OUTPUT_SLOT_CAP = 64;

	public sealed interface PreCheckResult permits PreOk, Failure {}

	/** Cheap-check output: recipe found, count valid, furnace position (may be null). */
	public record PreOk(
			ResourceLocation inputItem,
			ResourceLocation resultItem,
			int outputPerBatch,
			int batches,
			int totalOutput,
			int cookTicks,
			BlockPos furnacePos
	) implements PreCheckResult {
		public boolean furnaceNearby() { return furnacePos != null; }
	}

	public sealed interface Result permits Ok, Failure {
		boolean furnaceNearby();
	}

	/**
	 * Final smelt plan. {@code alreadyInFurnaceInput} is matching input already loaded in the
	 * furnace's input slot — Smelter pushes only the shortfall.
	 *
	 * {@code fuelPlan} is the ordered list of fuel components consumed for this smelt. The first
	 * entry may have {@code alreadyInFurnace > 0} when a pre-loaded fuel matches and is reused;
	 * later entries always have {@code alreadyInFurnace = 0} and require waiting for the fuel
	 * slot to drain before pushing. A single-fuel smelt is just a one-entry list.
	 */
	public record Ok(
			ResourceLocation inputItem,
			ResourceLocation resultItem,
			int totalOutput,
			int batches,
			int outputPerBatch,
			int cookTicks,
			BlockPos furnacePos,
			List<FuelComponent> fuelPlan,
			int alreadyInFurnaceInput
	) implements Result {
		@Override public boolean furnaceNearby() { return furnacePos != null; }
		public int inputToPush() { return Math.max(0, batches - alreadyInFurnaceInput); }
	}

	/**
	 * One fuel type in a smelt plan. {@code pieces} is the total count of this fuel consumed
	 * (including any already in the furnace); {@code alreadyInFurnace} is what was peeked from the
	 * pre-existing fuel slot. The shortfall to push from inventory is {@code pieces - alreadyInFurnace}.
	 */
	public record FuelComponent(ResourceLocation id, int pieces, int alreadyInFurnace) {
		public int toPush() { return Math.max(0, pieces - alreadyInFurnace); }
	}

	public record Failure(
			String reason,
			String message,
			List<MissingItem> missing,
			boolean furnaceNearby
	) implements Result, PreCheckResult {}

	public record MissingItem(String id, int count) {}

	/** Items already loaded in the furnace's input/fuel slots, pulled from a peek of the open menu. */
	public record ExtraStock(Item inputItem, int inputCount, Item fuelItem, int fuelCount) {
		public static final ExtraStock EMPTY = new ExtraStock(null, 0, null, 0);

		public static ExtraStock fromFurnaceSlots(ItemStack inputSlot, ItemStack fuelSlot) {
			Item iItem = inputSlot.isEmpty() ? null : inputSlot.getItem();
			int iCount = inputSlot.isEmpty() ? 0 : inputSlot.getCount();
			Item fItem = fuelSlot.isEmpty() ? null : fuelSlot.getItem();
			int fCount = fuelSlot.isEmpty() ? 0 : fuelSlot.getCount();
			return new ExtraStock(iItem, iCount, fItem, fCount);
		}
	}

	/** Cheap pre-check: parse, recipe lookup, furnace proximity, count cap. */
	public static PreCheckResult preCheck(String inputIdStr, int count, String fuelIdStr) {
		if (count <= 0) {
			return new Failure("unknown_item", "count must be >= 1", List.of(), false);
		}

		ResourceLocation inputId = ResourceLocation.tryParse(inputIdStr);
		if (inputId == null || !BuiltInRegistries.ITEM.containsKey(inputId)) {
			return new Failure("unknown_item",
					"no item with id '" + inputIdStr + "'",
					List.of(), false);
		}
		Item inputItem = BuiltInRegistries.ITEM.getValue(inputId);

		if (fuelIdStr != null && !fuelIdStr.isEmpty()) {
			ResourceLocation fId = ResourceLocation.tryParse(fuelIdStr);
			if (fId == null || !BuiltInRegistries.ITEM.containsKey(fId)) {
				return new Failure("unknown_item",
						"no item with id '" + fuelIdStr + "' (fuel)",
						List.of(), false);
			}
		}

		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return new Failure("internal_error", "not in world", List.of(), false);
		}

		BlockPos furnacePos = findNearestFurnace();
		boolean furnaceNearby = furnacePos != null;

		ClientRecipeBook book = mc.player.getRecipeBook();
		ContextMap ctx = SlotDisplayContext.fromLevel(mc.level);

		FurnaceRecipeDisplay match = null;
		ItemStack resultStack = null;

		outer:
		for (RecipeCollection coll : book.getCollections()) {
			for (RecipeDisplayEntry entry : coll.getRecipes()) {
				if (!(entry.display() instanceof FurnaceRecipeDisplay frd)) continue;
				List<ItemStack> ings = frd.ingredient().resolveForStacks(ctx);
				for (ItemStack is : ings) {
					if (!is.isEmpty() && is.getItem() == inputItem) {
						match = frd;
						resultStack = frd.result().resolveForFirstStack(ctx);
						break outer;
					}
				}
			}
		}

		if (match == null) {
			return new Failure("no_recipe",
					"no unlocked smelting recipe with input '" + inputIdStr + "'",
					List.of(), furnaceNearby);
		}
		if (resultStack == null || resultStack.isEmpty()) {
			return new Failure("internal_error",
					"smelting recipe for '" + inputIdStr + "' produced no result stack",
					List.of(), furnaceNearby);
		}

		int perBatch = resultStack.getCount();
		int batches = (count + perBatch - 1) / perBatch;
		int totalOutput = batches * perBatch;

		if (batches > FURNACE_OUTPUT_SLOT_CAP) {
			return new Failure("internal_error",
					"count " + count + " requires " + batches + " smelts (>64); split across calls",
					List.of(), furnaceNearby);
		}
		if (totalOutput > FURNACE_OUTPUT_SLOT_CAP) {
			return new Failure("internal_error",
					"totalOutput " + totalOutput + " exceeds furnace result-slot capacity (64)",
					List.of(), furnaceNearby);
		}

		return new PreOk(
				inputId,
				BuiltInRegistries.ITEM.getKey(resultStack.getItem()),
				perBatch, batches, totalOutput,
				match.duration(),
				furnacePos);
	}

	/**
	 * Final budget evaluation: inventory + {@code extra} (furnace contents) must cover the input
	 * and chosen fuel. Auto-fuel ranking prefers {@code extra.fuelItem} if it's a valid fuel and
	 * sufficient (so we don't pointlessly evict pre-loaded fuel).
	 */
	public static Result evaluate(PreOk pre, String fuelIdStr, ExtraStock extra) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return new Failure("internal_error", "not in world", List.of(), pre.furnaceNearby());
		}

		Item inputItem = BuiltInRegistries.ITEM.getValue(pre.inputItem());

		ResourceLocation explicitFuelId = null;
		Item explicitFuelItem = null;
		if (fuelIdStr != null && !fuelIdStr.isEmpty()) {
			explicitFuelId = ResourceLocation.tryParse(fuelIdStr);
			explicitFuelItem = BuiltInRegistries.ITEM.getValue(explicitFuelId);
		}

		Map<Item, Integer> have = inventoryCounts(mc);

		int extraInputForMatch = (extra.inputItem() == inputItem) ? extra.inputCount() : 0;
		int inputHave = have.getOrDefault(inputItem, 0) + extraInputForMatch;
		int inputNeeded = pre.batches();
		boolean furnaceNearby = pre.furnaceNearby();

		if (inputHave < inputNeeded) {
			List<MissingItem> missing = List.of(new MissingItem(pre.inputItem().toString(), inputNeeded - inputHave));
			return new Failure("missing_input",
					strip(pre.inputItem().toString()) + "×" + pre.totalOutput()
							+ " needs " + (inputNeeded - inputHave)
							+ " more " + strip(pre.inputItem().toString())
							+ furnaceSuffix(furnaceNearby),
					missing, furnaceNearby);
		}

		int totalBurnTicks = pre.batches() * pre.cookTicks();
		FuelValues fv = mc.level.fuelValues();
		List<FuelComponent> fuelPlan;

		if (explicitFuelItem != null) {
			ItemStack probe = new ItemStack(explicitFuelItem);
			if (!fv.isFuel(probe)) {
				return new Failure("missing_fuel",
						"'" + fuelIdStr + "' is not a valid fuel",
						List.of(new MissingItem(fuelIdStr, 1)), furnaceNearby);
			}
			int burnPerPiece = fv.burnDuration(probe);
			int fuelPiecesTotal = (totalBurnTicks + burnPerPiece - 1) / burnPerPiece;
			int extraFuelForMatch = (extra.fuelItem() == explicitFuelItem) ? extra.fuelCount() : 0;
			int fuelHave = have.getOrDefault(explicitFuelItem, 0) + extraFuelForMatch;
			if (fuelHave < fuelPiecesTotal) {
				return new Failure("missing_fuel",
						"need " + fuelPiecesTotal + " " + strip(fuelIdStr) + " for " + pre.totalOutput()
								+ " smelts; have " + fuelHave + furnaceSuffix(furnaceNearby),
						List.of(new MissingItem(fuelIdStr, fuelPiecesTotal - fuelHave)),
						furnaceNearby);
			}
			int alreadyInFurnace = Math.min(extraFuelForMatch, fuelPiecesTotal);
			fuelPlan = List.of(new FuelComponent(explicitFuelId, fuelPiecesTotal, alreadyInFurnace));
		} else {
			List<FuelComponent> picked = pickFuelPlan(fv, have, totalBurnTicks, extra);
			if (picked == null) {
				return buildMissingFuelFailure(fv, have, totalBurnTicks, pre, extra, furnaceNearby);
			}
			fuelPlan = picked;
		}

		if (!furnaceNearby) {
			return new Failure("requires_furnace",
					"no furnace within " + FURNACE_REACH + " blocks of player",
					List.of(), false);
		}

		int alreadyInputForPlan = Math.min(extraInputForMatch, inputNeeded);

		return new Ok(pre.inputItem(),
				pre.resultItem(),
				pre.totalOutput(), pre.batches(), pre.outputPerBatch(),
				pre.cookTicks(),
				pre.furnacePos(),
				fuelPlan,
				alreadyInputForPlan);
	}

	// --- Fuel ranking ---

	// Ordered high-preference (burn first) → low-preference (preserve). Pick the fuel whose loss
	// costs the least crafting potential, not the fuel that yields the most smelts. Coal_block /
	// lava_bucket are pure fuel — burning them strands no recipe. Logs are at the bottom because
	// each log → 4 planks → 8 sticks downstream, so a burned log is a deep crafting-DAG loss.
	private static final List<Predicate<ResourceLocation>> FUEL_TIERS = List.of(
			id -> id.getPath().equals("coal_block") || id.getPath().equals("lava_bucket"),
			id -> id.getPath().equals("coal") || id.getPath().equals("charcoal"),
			id -> id.getPath().equals("stick"),
			id -> id.getPath().endsWith("_sapling"),
			id -> id.getPath().endsWith("_planks"),
			id -> id.getPath().endsWith("_log") || id.getPath().endsWith("_stem")
	);

	/**
	 * Accumulating fuel selection. Walks tiers by least-crafting-potential-loss (see FUEL_TIERS),
	 * adding components until the burn budget closes. The pre-loaded slot (if a valid fuel) is
	 * consumed first to avoid pointless eviction. Within a tier, larger stacks are preferred and
	 * ties broken by id for determinism.
	 *
	 * Returns null when even the player's full fuel stock doesn't cover the budget. The caller is
	 * responsible for building a {@code missing_fuel} failure with the residual gap.
	 */
	private static List<FuelComponent> pickFuelPlan(FuelValues fv, Map<Item, Integer> have,
													 int totalBurnTicks, ExtraStock extra) {
		int remaining = totalBurnTicks;
		List<FuelComponent> components = new ArrayList<>();

		// Priority 1: pre-loaded fuel — use what's already in the slot before pulling from inventory.
		if (extra.fuelItem() != null) {
			ItemStack probe = new ItemStack(extra.fuelItem());
			if (fv.isFuel(probe)) {
				int burn = fv.burnDuration(probe);
				if (burn > 0) {
					int extraAvail = extra.fuelCount();
					int invAvail = have.getOrDefault(extra.fuelItem(), 0);
					int totalAvail = extraAvail + invAvail;
					int piecesNeeded = ceilDiv(remaining, burn);
					int take = Math.min(piecesNeeded, totalAvail);
					if (take > 0) {
						int alreadyInFurnace = Math.min(extraAvail, take);
						components.add(new FuelComponent(
								BuiltInRegistries.ITEM.getKey(extra.fuelItem()), take, alreadyInFurnace));
						remaining -= take * burn;
					}
					if (remaining <= 0) return components;
				}
			}
		}

		// Priority 2: tiered accumulation from inventory, walking least-crafting-loss → most.
		for (Predicate<ResourceLocation> tier : FUEL_TIERS) {
			List<Map.Entry<Item, Integer>> tierItems = new ArrayList<>();
			for (Map.Entry<Item, Integer> e : have.entrySet()) {
				ResourceLocation id = BuiltInRegistries.ITEM.getKey(e.getKey());
				if (tier.test(id)) tierItems.add(e);
			}
			tierItems.sort(Comparator
					.<Map.Entry<Item, Integer>>comparingInt(Map.Entry::getValue).reversed()
					.thenComparing(e -> BuiltInRegistries.ITEM.getKey(e.getKey()).toString()));

			for (Map.Entry<Item, Integer> e : tierItems) {
				Item item = e.getKey();
				ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
				int alreadyTaken = 0;
				for (FuelComponent c : components) {
					if (c.id().equals(id)) alreadyTaken += c.toPush();
				}
				int available = e.getValue() - alreadyTaken;
				if (available <= 0) continue;
				ItemStack probe = new ItemStack(item);
				if (!fv.isFuel(probe)) continue;
				int burn = fv.burnDuration(probe);
				if (burn <= 0) continue;
				int piecesNeeded = ceilDiv(remaining, burn);
				int take = Math.min(piecesNeeded, available);
				if (take > 0) {
					components.add(new FuelComponent(id, take, 0));
					remaining -= take * burn;
				}
				if (remaining <= 0) return components;
			}
		}

		return null;
	}

	/**
	 * Build a missing_fuel failure that names the actual shortfall. Suggests the cheapest fuel the
	 * player already has (if any) at a count sufficient to close the residual burn-tick gap; falls
	 * back to oak_planks when the player holds no usable fuel at all.
	 */
	private static Failure buildMissingFuelFailure(FuelValues fv, Map<Item, Integer> have,
												   int totalBurnTicks, PreOk pre,
												   ExtraStock extra, boolean furnaceNearby) {
		int coveredTicks = coveredBurnTicks(fv, have, extra);
		int gap = totalBurnTicks - coveredTicks;
		if (gap < 0) gap = 0;
		MissingItem suggestion = suggestFuelForGap(fv, have, gap);
		String covered = (coveredTicks > 0)
				? "have ~" + coveredTicks + " burn ticks across inventory + furnace"
				: "no usable fuel in inventory";
		String msg = covered + " (need " + totalBurnTicks
				+ " for " + pre.totalOutput() + " smelts); short " + gap + " ticks — need "
				+ suggestion.count() + " more " + strip(suggestion.id())
				+ " (or equivalent)" + furnaceSuffix(furnaceNearby);
		return new Failure("missing_fuel", msg, List.of(suggestion), furnaceNearby);
	}

	private static int coveredBurnTicks(FuelValues fv, Map<Item, Integer> have, ExtraStock extra) {
		int total = 0;
		for (Map.Entry<Item, Integer> e : have.entrySet()) {
			ItemStack probe = new ItemStack(e.getKey());
			if (!fv.isFuel(probe)) continue;
			int burn = fv.burnDuration(probe);
			if (burn > 0) total += burn * e.getValue();
		}
		if (extra.fuelItem() != null) {
			ItemStack probe = new ItemStack(extra.fuelItem());
			if (fv.isFuel(probe)) {
				int burn = fv.burnDuration(probe);
				if (burn > 0) total += burn * extra.fuelCount();
			}
		}
		return total;
	}

	/** Pick the highest-preference tier the player already has any of and frame the gap in that item. */
	private static MissingItem suggestFuelForGap(FuelValues fv, Map<Item, Integer> have, int gapTicks) {
		if (gapTicks <= 0) gapTicks = 200;  // floor at one smelt's worth
		for (Predicate<ResourceLocation> tier : FUEL_TIERS) {
			for (Map.Entry<Item, Integer> e : have.entrySet()) {
				ResourceLocation id = BuiltInRegistries.ITEM.getKey(e.getKey());
				if (!tier.test(id)) continue;
				ItemStack probe = new ItemStack(e.getKey());
				if (!fv.isFuel(probe)) continue;
				int burn = fv.burnDuration(probe);
				if (burn <= 0) continue;
				return new MissingItem(id.toString(), ceilDiv(gapTicks, burn));
			}
		}
		// Player has nothing useful — point at planks (renewable, mid-tier).
		return new MissingItem("minecraft:oak_planks", ceilDiv(gapTicks, 300));
	}

	private static int ceilDiv(int num, int den) { return (num + den - 1) / den; }

	// --- Helpers ---

	private static String strip(String id) {
		int colon = id.indexOf(':');
		return colon < 0 ? id : id.substring(colon + 1);
	}

	private static String furnaceSuffix(boolean nearby) {
		return nearby ? "" : "; no furnace within reach";
	}

	private static Map<Item, Integer> inventoryCounts(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null) return Map.of();
		Inventory inv = p.getInventory();
		Map<Item, Integer> out = new HashMap<>();
		addAll(out, inv.items);
		addAll(out, inv.armor);
		addAll(out, inv.offhand);
		return out;
	}

	private static void addAll(Map<Item, Integer> out, NonNullList<ItemStack> stacks) {
		for (ItemStack s : stacks) {
			if (!s.isEmpty()) out.merge(s.getItem(), s.getCount(), Integer::sum);
		}
	}

	private static BlockPos findNearestFurnace() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (p == null || level == null) return null;
		BlockPos origin = p.blockPosition();
		int r = FURNACE_REACH;
		double rSq = r * r;
		BlockPos best = null;
		double bestDistSq = Double.MAX_VALUE;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					cursor.setWithOffset(origin, dx, dy, dz);
					double d = cursor.distSqr(origin);
					if (d > rSq) continue;
					if (level.getBlockState(cursor).is(Blocks.FURNACE) && d < bestDistSq) {
						best = cursor.immutable();
						bestDistSq = d;
					}
				}
			}
		}
		return best;
	}
}
