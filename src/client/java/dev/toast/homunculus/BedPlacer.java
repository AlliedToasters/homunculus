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
import net.minecraft.world.level.block.BedBlock;
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
 * Places a bed (any 2-block bed item) near the player's feet.
 *
 * Beds are 2-block items: foot at the clicked cell, head one step further in the player's
 * horizontal facing direction. So the only valid candidates are cells in a cardinal direction
 * from the player — facing is set by Look.faceBlockTop(support), which points the player at the
 * foot's support block, fixing the horizontal yaw = direction toward foot = direction head extends.
 *
 * Search order: ring-2 cardinals first (more breathing room), then ring-1 cardinals.
 * For each candidate, both the foot cell and head cell must be open and have sturdy support below.
 */
public final class BedPlacer {
    private BedPlacer() {}

    private static final long PER_OP_TIMEOUT_MS = 2000;
    private static final long SELECT_SETTLE_MS = 100;
    private static final long ROT_SETTLE_MS = 50;
    private static final long SNEAK_SETTLE_MS = 100;
    private static final long PLACE_SETTLE_MS = 200;

    private static final int RING_1_OPEN_MIN = 3;

    private static final int[][] RING_1 = {
        { 0, -1}, { 1,  0}, { 0,  1}, {-1,  0},
        { 1, -1}, { 1,  1}, {-1,  1}, {-1, -1},
    };

    // (foot_dx, foot_dz, head_dx_from_foot, head_dz_from_foot)
    // Ring-2 cardinals first so the placed bed has more room; ring-1 as fallback.
    private static final int[][] BED_CANDIDATES = {
        { 0, -2,  0, -1},   // foot 2N, head 3N
        { 2,  0,  1,  0},   // foot 2E, head 3E
        { 0,  2,  0,  1},   // foot 2S, head 3S
        {-2,  0, -1,  0},   // foot 2W, head 3W
        { 0, -1,  0, -1},   // foot 1N, head 2N
        { 1,  0,  1,  0},   // foot 1E, head 2E
        { 0,  1,  0,  1},   // foot 1S, head 2S
        {-1,  0, -1,  0},   // foot 1W, head 2W
    };

    public sealed interface Result permits Ok, Failure {}
    public record Ok(int fx, int fy, int fz, int hx, int hy, int hz, String color, String facing) implements Result {}
    public record Failure(String reason, String message) implements Result {}

    /**
     * Places a bed near the player. If itemIdStr is null or blank, uses any *_bed found in
     * inventory; otherwise requires the specified item.
     */
    public static Result place(String itemIdStr) throws InterruptedException {
        Minecraft mc = Minecraft.getInstance();

        SearchOutcome outcome = supply(() -> searchAndPrecheck(mc, itemIdStr));
        if (outcome.failure != null) return outcome.failure;

        BlockPos foot = outcome.foot;
        BlockPos head = outcome.head;
        BlockPos support = foot.below();
        Item item = outcome.item;
        String resolvedId = outcome.itemId;

        Failure selectFailure = supply(() -> selectForPlacement(mc, item, resolvedId));
        if (selectFailure != null) return selectFailure;
        Thread.sleep(SELECT_SETTLE_MS);

        Boolean rotated = supply(() -> { Look.faceBlockTop(mc, support); return mc.player != null; });
        if (rotated == null || !rotated) return new Failure("internal_error", "player vanished during rotate");
        Thread.sleep(ROT_SETTLE_MS);

        Boolean priorShift = supply(() -> beginSneak(mc));
        if (priorShift == null) return new Failure("internal_error", "player vanished after rotate");

        try {
            Thread.sleep(SNEAK_SETTLE_MS);
            supply(() -> { sendPlacePacket(mc, support); return null; });
            Thread.sleep(PLACE_SETTLE_MS);
            return supply(() -> verifyPlacement(mc, foot, head, resolvedId));
        } finally {
            final boolean restore = priorShift;
            supply(() -> { endSneak(mc, restore); return null; });
        }
    }

    private record SearchOutcome(BlockPos foot, BlockPos head, Item item, String itemId, Failure failure) {
        static SearchOutcome ok(BlockPos foot, BlockPos head, Item item, String itemId) {
            return new SearchOutcome(foot, head, item, itemId, null);
        }
        static SearchOutcome fail(Failure f) { return new SearchOutcome(null, null, null, null, f); }
    }

    private static SearchOutcome searchAndPrecheck(Minecraft mc, String itemIdStr) {
        LocalPlayer p = mc.player;
        if (p == null) return SearchOutcome.fail(new Failure("internal_error", "no player (not in world)"));
        if (mc.gameMode == null) return SearchOutcome.fail(new Failure("internal_error", "no gameMode"));
        ClientLevel level = mc.level;
        if (level == null) return SearchOutcome.fail(new Failure("internal_error", "no level"));
        if (p.containerMenu != p.inventoryMenu) {
            return SearchOutcome.fail(new Failure("internal_error",
                "another inventory screen is open; close it before placing"));
        }

        // Resolve the bed item to use.
        Item item;
        String resolvedId;
        if (itemIdStr != null && !itemIdStr.isBlank()) {
            ResourceLocation rl = ResourceLocation.tryParse(itemIdStr);
            if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) {
                return SearchOutcome.fail(new Failure("no_bed_in_inventory",
                    "unknown item id '" + itemIdStr + "'"));
            }
            Item candidate = BuiltInRegistries.ITEM.getValue(rl);
            if (!(candidate instanceof BlockItem bi) || !(bi.getBlock() instanceof BedBlock)) {
                return SearchOutcome.fail(new Failure("no_bed_in_inventory",
                    "'" + itemIdStr + "' is not a bed item"));
            }
            item = candidate;
            resolvedId = itemIdStr;
        } else {
            item = null;
            resolvedId = null;
            for (ItemStack s : p.getInventory().items) {
                if (s.isEmpty()) continue;
                if (!(s.getItem() instanceof BlockItem bi)) continue;
                if (!(bi.getBlock() instanceof BedBlock)) continue;
                item = s.getItem();
                resolvedId = BuiltInRegistries.ITEM.getKey(item).toString();
                break;
            }
            if (item == null) {
                return SearchOutcome.fail(new Failure("no_bed_in_inventory", "no bed item in inventory"));
            }
        }

        if (findBed(p.getInventory().items, item) < 0) {
            return SearchOutcome.fail(new Failure("no_bed_in_inventory",
                "no '" + resolvedId + "' in inventory"));
        }

        // Anti-casing check: enough open tiles around player that we won't box ourselves in.
        BlockPos feet = p.blockPosition();
        int openCount = 0;
        for (int[] off : RING_1) {
            BlockState s = level.getBlockState(feet.offset(off[0], 0, off[1]));
            if (s.isAir() || s.canBeReplaced()) openCount++;
        }
        if (openCount < RING_1_OPEN_MIN) {
            return SearchOutcome.fail(new Failure("no_space",
                "only " + openCount + "/8 adjacent tiles clear (need " + RING_1_OPEN_MIN
                    + "+) — relocate to open ground"));
        }

        // Search for a valid (foot, head) pair. For each candidate, the head extends one more
        // step in the same cardinal direction, matching the facing that faceBlockTop will set.
        //
        // Failure-reason precedence: at least one candidate had a placeable foot (solo) but
        // the head pair failed → no_pair_spot. Any candidate hit blocks_doorway → blocks_doorway.
        // Otherwise no candidate could even place a foot → no_placeable_spot.
        boolean footPlaceableButPairFailed = false;
        boolean sawDoorAdjacent = false;
        for (int[] c : BED_CANDIDATES) {
            BlockPos foot = feet.offset(c[0], 0, c[1]);
            BlockPos head = foot.offset(c[2], 0, c[3]);

            if (!isOpen(level, foot)) continue;
            BlockPos footSupport = foot.below();
            if (!level.getBlockState(footSupport).isFaceSturdy(level, footSupport, Direction.UP)) continue;
            if (blocksDoorway(level, foot)) { sawDoorAdjacent = true; continue; }
            // Foot is placeable on its own. Now check the pair.

            if (!isOpen(level, head)) { footPlaceableButPairFailed = true; continue; }
            BlockPos headSupport = head.below();
            if (!level.getBlockState(headSupport).isFaceSturdy(level, headSupport, Direction.UP)) {
                footPlaceableButPairFailed = true;
                continue;
            }
            if (blocksDoorway(level, head)) { sawDoorAdjacent = true; continue; }

            final Item finalItem = item;
            final String finalId = resolvedId;
            return SearchOutcome.ok(foot, head, finalItem, finalId);
        }

        if (footPlaceableButPairFailed) {
            return SearchOutcome.fail(new Failure("no_pair_spot",
                "found placeable foot positions, but every head cell was blocked or lacked sturdy support — relocate to more open ground"));
        }
        if (sawDoorAdjacent) {
            return SearchOutcome.fail(new Failure("blocks_doorway",
                "every candidate bed cell would block a nearby door/gate — travel 2-3 blocks before retrying"));
        }
        return SearchOutcome.fail(new Failure("no_placeable_spot",
            "no flat ground within 2 blocks for bed foot (open above, sturdy below) — relocate and retry"));
    }

    private static boolean isOpen(ClientLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.isAir() || s.canBeReplaced();
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

    private static Failure selectForPlacement(Minecraft mc, Item item, String itemId) {
        LocalPlayer p = mc.player;
        if (p == null) return new Failure("internal_error", "no player");
        Inventory inv = p.getInventory();
        int slot = findBed(inv.items, item);
        if (slot < 0) return new Failure("no_bed_in_inventory", "no '" + itemId + "' in inventory");
        if (Inventory.isHotbarSlot(slot)) {
            inv.selected = slot;
        } else {
            mc.gameMode.handleInventoryMouseClick(
                InventoryMenu.CONTAINER_ID, slot, inv.selected, ClickType.SWAP, p);
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

    private static Result verifyPlacement(Minecraft mc, BlockPos foot, BlockPos head, String itemId) {
        ClientLevel level = mc.level;
        if (level == null) return new Failure("internal_error", "level vanished mid-op");

        if (!(level.getBlockState(foot).getBlock() instanceof BedBlock)
                || !(level.getBlockState(head).getBlock() instanceof BedBlock)) {
            return new Failure("internal_error",
                "bed placement did not take — server rejected or terrain shifted");
        }

        // Color: "minecraft:red_bed" → "red"
        String color = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        if (color.endsWith("_bed")) color = color.substring(0, color.length() - 4);

        // Facing: direction from foot to head
        int dx = head.getX() - foot.getX();
        int dz = head.getZ() - foot.getZ();
        String facing = dx == 0 && dz < 0 ? "north"
                      : dx > 0 && dz == 0 ? "east"
                      : dx == 0 && dz > 0 ? "south"
                      : "west";

        return new Ok(foot.getX(), foot.getY(), foot.getZ(),
                      head.getX(), head.getY(), head.getZ(),
                      color, facing);
    }

    /** Scans main inventory for any bed item (targetItem==null) or a specific one. */
    private static int findBed(NonNullList<ItemStack> items, Item targetItem) {
        for (int i = 0; i < items.size(); i++) {
            ItemStack s = items.get(i);
            if (s.isEmpty()) continue;
            if (!(s.getItem() instanceof BlockItem bi)) continue;
            if (!(bi.getBlock() instanceof BedBlock)) continue;
            if (targetItem != null && s.getItem() != targetItem) continue;
            return i;
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
