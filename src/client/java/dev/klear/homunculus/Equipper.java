package dev.klear.homunculus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.equipment.Equippable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Auto-equips the "best" item per role across the player's inventory.
 *
 * Hotbar layout (0-indexed):
 *   0 sword, 1 axe, 2 pickaxe, 3 shovel, 4 hoe, 5 food, 6 building blocks,
 *   7-8 untouched (caller-managed misc).
 *
 * Armor: best per HEAD/CHEST/LEGS/FEET via Equippable component.
 *
 * Ranking:
 *   - Tools/armor: material tier from id prefix (netherite > diamond > iron/turtle >
 *     stone/chainmail > golden > wooden/leather), tiebreak by lower DAMAGE.
 *   - Food: highest FoodProperties.nutrition(), tiebreak by saturation.
 *   - Building: cheapest first (curated tier list), tiebreak by largest stack count;
 *     items not in the curated list are not considered.
 *
 * Click strategy: hotbar swaps go through ClickType.SWAP (one packet); armor swaps go
 * through PICKUP-PICKUP-PICKUP (cursor cycle), which works regardless of whether the
 * armor slot is empty or occupied. Settle delays mirror Crafter's, so menu stateIds
 * stay synced across the rapid click sequence.
 */
public final class Equipper {
	private Equipper() {}

	private static final long PER_OP_TIMEOUT_MS = 2000;
	private static final long CLICK_SETTLE_MS = 50;
	private static final long FINAL_SETTLE_MS = 100;

	public sealed interface Result permits Ok, Failure {}
	public record Ok(Map<String, String> equipped, List<Change> changes, String message) implements Result {}
	public record Failure(String reason, String message) implements Result {}
	public record Change(String role, String from, String to) {}

	public static Result equipAll() throws InterruptedException {
		Minecraft mc = Minecraft.getInstance();

		Failure pre = supply(() -> {
			LocalPlayer p = mc.player;
			if (p == null) return new Failure("internal_error", "no player (not in world)");
			if (mc.gameMode == null) return new Failure("internal_error", "no gameMode");
			if (p.containerMenu != p.inventoryMenu) {
				return new Failure("internal_error",
						"another inventory screen is open; close it before equipping");
			}
			return null;
		});
		if (pre != null) return pre;

		List<Change> changes = new ArrayList<>();

		applyHotbarRole(mc, "sword",    0, Equipper::isSword,    Equipper::compareTool,     changes);
		applyHotbarRole(mc, "axe",      1, Equipper::isAxe,      Equipper::compareTool,     changes);
		applyHotbarRole(mc, "pickaxe",  2, Equipper::isPickaxe,  Equipper::compareTool,     changes);
		applyHotbarRole(mc, "shovel",   3, Equipper::isShovel,   Equipper::compareTool,     changes);
		applyHotbarRole(mc, "hoe",      4, Equipper::isHoe,      Equipper::compareTool,     changes);
		applyHotbarRole(mc, "food",     5, Equipper::isFood,     Equipper::compareFood,     changes);
		applyHotbarRole(mc, "building", 6, Equipper::isBuilding, Equipper::compareBuilding, changes);

		applyArmorRole(mc, EquipmentSlot.HEAD,  "head",  changes);
		applyArmorRole(mc, EquipmentSlot.CHEST, "chest", changes);
		applyArmorRole(mc, EquipmentSlot.LEGS,  "legs",  changes);
		applyArmorRole(mc, EquipmentSlot.FEET,  "feet",  changes);

		Thread.sleep(FINAL_SETTLE_MS);
		// Safety net: if anything left an item on the cursor, drop it back to inventory.
		supply(() -> { dropCursorIfNonEmpty(mc); return null; });

		Map<String, String> equipped = supply(() -> snapshotEquipped(mc));
		String msg = changes.isEmpty()
				? "no changes — inventory already optimal"
				: changes.size() + " change(s): " + summarize(changes);
		return new Ok(equipped, changes, msg);
	}

	/* ============================ Role drivers ============================ */

	private static void applyHotbarRole(
			Minecraft mc,
			String roleLabel,
			int targetHotbarIndex,
			Predicate<ItemStack> matches,
			Comparator<ItemStack> better,
			List<Change> changes) throws InterruptedException {

		int targetMenuSlot = hotbarIndexToMenuSlot(targetHotbarIndex);
		int sourceMenuSlot = supply(() -> findBestSource(mc, matches, better, targetMenuSlot));
		if (sourceMenuSlot < 0) return;

		String fromId = supply(() -> idAtMenuSlot(mc, targetMenuSlot));
		String toId   = supply(() -> idAtMenuSlot(mc, sourceMenuSlot));

		supply(() -> {
			mc.gameMode.handleInventoryMouseClick(
					InventoryMenu.CONTAINER_ID, sourceMenuSlot, targetHotbarIndex,
					ClickType.SWAP, mc.player);
			return null;
		});
		Thread.sleep(CLICK_SETTLE_MS);

		String afterId = supply(() -> idAtMenuSlot(mc, targetMenuSlot));
		if (toId != null && toId.equals(afterId)) {
			changes.add(new Change(roleLabel, fromId, afterId));
		}
	}

	private static void applyArmorRole(
			Minecraft mc,
			EquipmentSlot armorSlot,
			String roleLabel,
			List<Change> changes) throws InterruptedException {

		int targetMenuSlot = armorMenuSlot(armorSlot);
		int sourceMenuSlot = supply(() -> findBestArmorSource(mc, armorSlot, targetMenuSlot));
		if (sourceMenuSlot < 0 || sourceMenuSlot == targetMenuSlot) return;

		String fromId = supply(() -> idAtMenuSlot(mc, targetMenuSlot));
		String toId   = supply(() -> idAtMenuSlot(mc, sourceMenuSlot));

		supply(() -> {
			mc.gameMode.handleInventoryMouseClick(
					InventoryMenu.CONTAINER_ID, sourceMenuSlot, 0, ClickType.PICKUP, mc.player);
			return null;
		});
		Thread.sleep(CLICK_SETTLE_MS);
		supply(() -> {
			mc.gameMode.handleInventoryMouseClick(
					InventoryMenu.CONTAINER_ID, targetMenuSlot, 0, ClickType.PICKUP, mc.player);
			return null;
		});
		Thread.sleep(CLICK_SETTLE_MS);
		supply(() -> {
			mc.gameMode.handleInventoryMouseClick(
					InventoryMenu.CONTAINER_ID, sourceMenuSlot, 0, ClickType.PICKUP, mc.player);
			return null;
		});
		Thread.sleep(CLICK_SETTLE_MS);

		String afterId = supply(() -> idAtMenuSlot(mc, targetMenuSlot));
		if (toId != null && toId.equals(afterId)) {
			changes.add(new Change(roleLabel, fromId, afterId));
		}
	}

	/* ============================ Search ============================ */

	private static int findBestSource(
			Minecraft mc,
			Predicate<ItemStack> matches,
			Comparator<ItemStack> better,
			int targetMenuSlot) {
		LocalPlayer p = mc.player;
		if (p == null) return -1;
		Inventory inv = p.getInventory();
		ItemStack bestStack = ItemStack.EMPTY;
		int bestMenuSlot = -1;
		// Scan all 36 inventory slots (hotbar 0..8 + main 9..35). We allow candidates from
		// hotbar 7-8 too — if a tool got stashed there, we'll pull it into the right slot.
		// The displaced item from the role slot ends up at the source slot (SWAP semantics).
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.items.get(i);
			if (s.isEmpty() || !matches.test(s)) continue;
			int menuSlot = i < 9 ? 36 + i : i;
			if (bestStack.isEmpty() || better.compare(s, bestStack) > 0) {
				bestStack = s;
				bestMenuSlot = menuSlot;
			}
		}
		if (bestMenuSlot < 0 || bestMenuSlot == targetMenuSlot) return -1;
		return bestMenuSlot;
	}

	private static int findBestArmorSource(Minecraft mc, EquipmentSlot armorSlot, int targetMenuSlot) {
		LocalPlayer p = mc.player;
		if (p == null) return -1;
		Inventory inv = p.getInventory();

		Predicate<ItemStack> matches = stack -> {
			Equippable eq = stack.get(DataComponents.EQUIPPABLE);
			return eq != null && eq.slot() == armorSlot;
		};
		Comparator<ItemStack> better = Equipper::compareTool;

		ItemStack bestStack = ItemStack.EMPTY;
		int bestMenuSlot = -1;

		ItemStack equipped = inv.armor.get(armorSlot.getIndex());
		if (!equipped.isEmpty() && matches.test(equipped)) {
			bestStack = equipped;
			bestMenuSlot = targetMenuSlot;
		}
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.items.get(i);
			if (s.isEmpty() || !matches.test(s)) continue;
			int menuSlot = i < 9 ? 36 + i : i;
			if (bestStack.isEmpty() || better.compare(s, bestStack) > 0) {
				bestStack = s;
				bestMenuSlot = menuSlot;
			}
		}
		return bestMenuSlot;
	}

	/* ============================ Predicates ============================ */

	private static boolean isSword(ItemStack s)    { return s.getItem() instanceof SwordItem; }
	private static boolean isAxe(ItemStack s)      { return s.getItem() instanceof AxeItem; }
	private static boolean isPickaxe(ItemStack s)  { return s.getItem() instanceof PickaxeItem; }
	private static boolean isShovel(ItemStack s)   { return s.getItem() instanceof ShovelItem; }
	private static boolean isHoe(ItemStack s)      { return s.getItem() instanceof HoeItem; }
	private static boolean isFood(ItemStack s)     { return s.has(DataComponents.FOOD); }
	private static boolean isBuilding(ItemStack s) {
		return BUILDING_BLOCK_TIER.containsKey(itemPath(s.getItem()));
	}

	/* ============================ Comparators ============================ */

	private static int compareTool(ItemStack a, ItemStack b) {
		int ta = materialTier(a.getItem()), tb = materialTier(b.getItem());
		if (ta != tb) return Integer.compare(ta, tb);
		return Integer.compare(damage(b), damage(a));
	}

	private static int compareFood(ItemStack a, ItemStack b) {
		FoodProperties fa = a.get(DataComponents.FOOD), fb = b.get(DataComponents.FOOD);
		if (fa == null || fb == null) return Boolean.compare(fa != null, fb != null);
		if (fa.nutrition() != fb.nutrition()) return Integer.compare(fa.nutrition(), fb.nutrition());
		return Float.compare(fa.saturation(), fb.saturation());
	}

	private static int compareBuilding(ItemStack a, ItemStack b) {
		int ta = buildingTier(a.getItem()), tb = buildingTier(b.getItem());
		if (ta != tb) return Integer.compare(tb, ta); // lower tier = better; flip
		return Integer.compare(a.getCount(), b.getCount());
	}

	private static int materialTier(Item item) {
		String path = itemPath(item);
		if (path.startsWith("netherite_")) return 5;
		if (path.startsWith("diamond_")) return 4;
		if (path.startsWith("iron_") || path.startsWith("turtle_")) return 3;
		if (path.startsWith("chainmail_") || path.startsWith("stone_")) return 2;
		if (path.startsWith("golden_")) return 1;
		if (path.startsWith("wooden_") || path.startsWith("leather_")) return 0;
		return -1;
	}

	private static int buildingTier(Item item) {
		Integer t = BUILDING_BLOCK_TIER.get(itemPath(item));
		return t == null ? Integer.MAX_VALUE : t;
	}

	private static int damage(ItemStack s) {
		Integer d = s.get(DataComponents.DAMAGE);
		return d == null ? 0 : d;
	}

	/* ============================ Slot/id helpers ============================ */

	private static int hotbarIndexToMenuSlot(int hotbarIndex) {
		return 36 + hotbarIndex;
	}

	private static int armorMenuSlot(EquipmentSlot s) {
		return switch (s) {
			case HEAD -> 5;
			case CHEST -> 6;
			case LEGS -> 7;
			case FEET -> 8;
			default -> throw new IllegalArgumentException("not an armor slot: " + s);
		};
	}

	private static String itemPath(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).getPath();
	}

	private static String idOf(ItemStack s) {
		if (s.isEmpty()) return null;
		return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
	}

	private static String idAtMenuSlot(Minecraft mc, int menuSlot) {
		LocalPlayer p = mc.player;
		if (p == null) return null;
		Inventory inv = p.getInventory();
		return idOf(stackAtMenuSlot(inv, menuSlot));
	}

	private static ItemStack stackAtMenuSlot(Inventory inv, int menuSlot) {
		// InventoryMenu layout: 0 result, 1-4 craft, 5 head, 6 chest, 7 legs, 8 feet,
		// 9-35 main inv (matches inv.items[9..35]), 36-44 hotbar (inv.items[0..8]),
		// 45 offhand.
		if (menuSlot >= 5 && menuSlot <= 8) {
			// armor: head=5→idx3, chest=6→idx2, legs=7→idx1, feet=8→idx0
			return inv.armor.get(8 - menuSlot);
		}
		if (menuSlot >= 9 && menuSlot <= 35) return inv.items.get(menuSlot);
		if (menuSlot >= 36 && menuSlot <= 44) return inv.items.get(menuSlot - 36);
		if (menuSlot == 45) return inv.offhand.get(0);
		return ItemStack.EMPTY;
	}

	private static Map<String, String> snapshotEquipped(Minecraft mc) {
		Map<String, String> map = new LinkedHashMap<>();
		LocalPlayer p = mc.player;
		if (p == null) return map;
		Inventory inv = p.getInventory();
		map.put("sword",    roleSlotIdIf(inv.items.get(0), Equipper::isSword));
		map.put("axe",      roleSlotIdIf(inv.items.get(1), Equipper::isAxe));
		map.put("pickaxe",  roleSlotIdIf(inv.items.get(2), Equipper::isPickaxe));
		map.put("shovel",   roleSlotIdIf(inv.items.get(3), Equipper::isShovel));
		map.put("hoe",      roleSlotIdIf(inv.items.get(4), Equipper::isHoe));
		map.put("food",     roleSlotIdIf(inv.items.get(5), Equipper::isFood));
		map.put("building", roleSlotIdIf(inv.items.get(6), Equipper::isBuilding));
		map.put("head",     idOf(inv.armor.get(EquipmentSlot.HEAD.getIndex())));
		map.put("chest",    idOf(inv.armor.get(EquipmentSlot.CHEST.getIndex())));
		map.put("legs",     idOf(inv.armor.get(EquipmentSlot.LEGS.getIndex())));
		map.put("feet",     idOf(inv.armor.get(EquipmentSlot.FEET.getIndex())));
		return map;
	}

	private static String roleSlotIdIf(ItemStack s, Predicate<ItemStack> matches) {
		if (s.isEmpty() || !matches.test(s)) return null;
		return idOf(s);
	}

	private static String summarize(List<Change> changes) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < changes.size(); i++) {
			if (i > 0) sb.append(", ");
			Change c = changes.get(i);
			sb.append(c.role()).append("=").append(stripNs(c.to()));
		}
		return sb.toString();
	}

	private static String stripNs(String id) {
		if (id == null) return "none";
		int colon = id.indexOf(':');
		return colon < 0 ? id : id.substring(colon + 1);
	}

	/* ============================ Cursor cleanup ============================ */

	/** If something is on the player's pickup cursor, deposit it into the first empty
	 *  inventory slot via PICKUP. No-op if cursor is empty. */
	private static void dropCursorIfNonEmpty(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.gameMode == null) return;
		ItemStack carried = p.containerMenu.getCarried();
		if (carried.isEmpty()) return;
		// Find first empty main inv slot, fall back to hotbar.
		int dest = -1;
		Inventory inv = p.getInventory();
		for (int i = 9; i < 36; i++) if (inv.items.get(i).isEmpty()) { dest = i; break; }
		if (dest < 0) {
			for (int i = 0; i < 9; i++) if (inv.items.get(i).isEmpty()) { dest = 36 + i; break; }
		}
		if (dest < 0) return; // inventory full; cursor will resolve when player closes screen
		mc.gameMode.handleInventoryMouseClick(
				InventoryMenu.CONTAINER_ID, dest, 0, ClickType.PICKUP, p);
	}

	/* ============================ Building tier table ============================ */

	private static final Map<String, Integer> BUILDING_BLOCK_TIER = new HashMap<>();
	static {
		// Tier 0: trash / pathing-friendly cheap blocks
		for (String x : new String[] {
				"dirt", "coarse_dirt", "rooted_dirt", "grass_block", "podzol", "mycelium",
				"mud", "packed_mud", "cobblestone", "cobbled_deepslate", "netherrack", "blackstone",
		}) BUILDING_BLOCK_TIER.put(x, 0);
		// Tier 1: cheap stone
		for (String x : new String[] {
				"stone", "deepslate", "granite", "diorite", "andesite", "tuff",
				"basalt", "smooth_basalt", "end_stone", "sandstone", "red_sandstone",
				"calcite", "dripstone_block",
		}) BUILDING_BLOCK_TIER.put(x, 1);
		// Tier 2: wood
		for (String x : new String[] {
				"oak_planks", "spruce_planks", "birch_planks", "jungle_planks", "acacia_planks",
				"dark_oak_planks", "mangrove_planks", "cherry_planks", "bamboo_planks",
				"crimson_planks", "warped_planks",
				"oak_log", "spruce_log", "birch_log", "jungle_log", "acacia_log",
				"dark_oak_log", "mangrove_log", "cherry_log",
				"stripped_oak_log", "stripped_spruce_log", "stripped_birch_log",
				"stripped_jungle_log", "stripped_acacia_log", "stripped_dark_oak_log",
				"stripped_mangrove_log", "stripped_cherry_log",
		}) BUILDING_BLOCK_TIER.put(x, 2);
		// Tier 3: processed stone
		for (String x : new String[] {
				"stone_bricks", "mossy_cobblestone", "mossy_stone_bricks",
				"polished_andesite", "polished_diorite", "polished_granite",
				"polished_deepslate", "polished_blackstone", "smooth_stone",
				"smooth_sandstone", "smooth_red_sandstone", "deepslate_bricks",
				"polished_basalt", "bricks", "nether_bricks", "red_nether_bricks",
				"chiseled_stone_bricks", "chiseled_deepslate", "chiseled_sandstone",
		}) BUILDING_BLOCK_TIER.put(x, 3);
		// Tier 4: semi-valuable
		for (String x : new String[] {
				"iron_block", "copper_block", "raw_iron_block", "raw_copper_block",
				"amethyst_block",
		}) BUILDING_BLOCK_TIER.put(x, 4);
		// Tier 5: precious — last resort
		for (String x : new String[] {
				"gold_block", "diamond_block", "emerald_block", "netherite_block",
				"raw_gold_block", "lapis_block",
		}) BUILDING_BLOCK_TIER.put(x, 5);
	}

	/* ============================ Client-thread bridge ============================ */

	private static <T> T supply(Supplier<T> task) {
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
