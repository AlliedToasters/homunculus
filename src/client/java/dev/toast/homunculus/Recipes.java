package dev.toast.homunculus;

import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Recipe lookup, ingredient-gap computation, and crafting-table proximity. Reads from
 * {@link ClientRecipeBook} (Mojang's display-side API) so this works in both single-player and
 * multiplayer — the integrated server pushes the same {@code ClientboundRecipeBookAddPacket}s its
 * dedicated counterpart does. Caveat: only recipes the player has unlocked are visible. In vanilla,
 * picking up an ingredient unlocks recipes that use it, so an agent that has the ingredients will
 * have the recipe.
 *
 * Must be invoked on the client thread (touches MinecraftClient state, player inventory, world).
 */
public final class Recipes {
	private Recipes() {}

	private static final int CRAFTING_TABLE_REACH = 4;

	public sealed interface Result permits Ok, Failure {
		boolean requiresCraftingTable();
		boolean craftingTableNearby();
	}

	public record Ok(
			ResourceLocation resultItem,
			int totalOutput,
			int batches,
			int outputPerBatch,
			boolean is3x3,
			RecipeDisplayId displayId,
			boolean craftingTableNearby,
			BlockPos craftingTablePos
	) implements Result {
		@Override public boolean requiresCraftingTable() { return is3x3; }
	}

	public record Failure(
			String reason,
			String message,
			List<MissingItem> missing,
			boolean requiresCraftingTable,
			boolean craftingTableNearby
	) implements Result {}

	public record MissingItem(String id, int count) {}

	public static Result evaluate(String itemIdStr, int count) {
		if (count <= 0) {
			return new Failure("unknown_item", "count must be >= 1", List.of(), false, false);
		}

		ResourceLocation itemId = ResourceLocation.tryParse(itemIdStr);
		if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) {
			return new Failure("unknown_item",
					"no item with id '" + itemIdStr + "'",
					List.of(), false, false);
		}
		Item item = BuiltInRegistries.ITEM.getValue(itemId);

		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return new Failure("internal_error", "not in world", List.of(), false, false);
		}

		ClientRecipeBook book = mc.player.getRecipeBook();
		ContextMap ctx = SlotDisplayContext.fromLevel(mc.level);

		RecipeDisplayEntry match = null;
		int perBatch = 0;
		boolean is3x3 = false;

		outer:
		for (RecipeCollection coll : book.getCollections()) {
			for (RecipeDisplayEntry entry : coll.getRecipes()) {
				RecipeDisplay disp = entry.display();
				boolean entryIs3x3;
				if (disp instanceof ShapedCraftingRecipeDisplay shaped) {
					entryIs3x3 = shaped.width() > 2 || shaped.height() > 2;
				} else if (disp instanceof ShapelessCraftingRecipeDisplay shapeless) {
					entryIs3x3 = shapeless.ingredients().size() > 4;
				} else {
					continue; // furnace/smithing/stonecutter — not a crafting-grid recipe
				}
				for (ItemStack rs : entry.resultItems(ctx)) {
					if (!rs.isEmpty() && rs.getItem() == item) {
						match = entry;
						perBatch = rs.getCount();
						is3x3 = entryIs3x3;
						break outer;
					}
				}
			}
		}

		if (match == null) {
			return new Failure("no_recipe",
					"no unlocked crafting recipe yields '" + itemIdStr
							+ "' (recipe must be unlocked — pick up an ingredient first)",
					List.of(), false, false);
		}

		Optional<List<Ingredient>> reqOpt = match.craftingRequirements();
		if (reqOpt.isEmpty()) {
			return new Failure("internal_error",
					"recipe for '" + itemIdStr + "' has no fixed ingredient list (special/dynamic recipe)",
					List.of(), is3x3, false);
		}
		List<Ingredient> ingredients = reqOpt.get();

		BlockPos tablePos = findNearestCraftingTable();
		boolean tableNearby = tablePos != null;

		int batches = (count + perBatch - 1) / perBatch;
		int totalOutput = batches * perBatch;

		// Compute inventory FIRST so canonicalItem() can prefer items the
		// player actually holds — critical for tag-based ingredients like
		// #planks. Without this, every wood-derived recipe demands the
		// FIRST item in the tag (oak_planks), even when the player has
		// hundreds of birch_planks. Observed in probe-validate-r5 T12+:
		// birch-forest spawn, 116 birch_planks, crafting_table loop-fails
		// because canonicalItem returned oak_planks unconditionally.
		Map<Item, Integer> have = inventoryCounts(mc);

		Map<Item, Integer> required = new LinkedHashMap<>();
		Map<Item, Integer> consumed = new LinkedHashMap<>();
		for (Ingredient ing : ingredients) {
			Item canonical = canonicalItem(ing, have, consumed, batches);
			if (canonical == null) continue;
			required.merge(canonical, batches, Integer::sum);
			consumed.merge(canonical, batches, Integer::sum);
		}

		List<MissingItem> missing = new ArrayList<>();
		for (var e : required.entrySet()) {
			int gap = e.getValue() - have.getOrDefault(e.getKey(), 0);
			if (gap > 0) {
				missing.add(new MissingItem(BuiltInRegistries.ITEM.getKey(e.getKey()).toString(), gap));
			}
		}

		if (!missing.isEmpty()) {
			return new Failure("missing_ingredients",
					buildMissingMessage(itemIdStr, missing, is3x3, tableNearby),
					missing, is3x3, tableNearby);
		}
		if (is3x3 && !tableNearby) {
			return new Failure("requires_crafting_table",
					"no crafting table within " + CRAFTING_TABLE_REACH + " blocks",
					List.of(), true, false);
		}

		return new Ok(itemId, totalOutput, batches, perBatch, is3x3, match.id(), tableNearby, tablePos);
	}

	private static String buildMissingMessage(String item, List<MissingItem> missing, boolean is3x3, boolean tableNearby) {
		StringBuilder sb = new StringBuilder(strip(item)).append(" needs ");
		for (int i = 0; i < missing.size(); i++) {
			if (i > 0) sb.append(i == missing.size() - 1 ? " and " : ", ");
			sb.append(missing.get(i).count()).append(" more ").append(strip(missing.get(i).id()));
		}
		if (is3x3 && !tableNearby) sb.append("; no crafting table within reach");
		return sb.toString();
	}

	private static String strip(String id) {
		int colon = id.indexOf(':');
		return colon < 0 ? id : id.substring(colon + 1);
	}

	@SuppressWarnings("deprecation")  // Ingredient.items() is the only public surface for canonical lookup in 1.21.4
	private static Item canonicalItem(Ingredient ing, Map<Item, Integer> have, Map<Item, Integer> consumed, int needPerSlot) {
		// Prefer any tag-matching item the player has enough of, after
		// subtracting prior slots' allocations. Falls back to the first
		// tag item for the "no candidates" case (which will surface as
		// a missing-ingredient error using the canonical id).
		Item fallback = null;
		for (var holder : ing.items().toList()) {
			Item item = holder.value();
			if (fallback == null) fallback = item;
			int available = have.getOrDefault(item, 0) - consumed.getOrDefault(item, 0);
			if (available >= needPerSlot) return item;
		}
		return fallback;
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

	private static BlockPos findNearestCraftingTable() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (p == null || level == null) return null;
		BlockPos origin = p.blockPosition();
		int r = CRAFTING_TABLE_REACH;
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
					if (level.getBlockState(cursor).is(Blocks.CRAFTING_TABLE) && d < bestDistSq) {
						best = cursor.immutable();
						bestDistSq = d;
					}
				}
			}
		}
		return best;
	}
}
