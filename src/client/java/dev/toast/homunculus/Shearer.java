package dev.toast.homunculus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Right-clicks the nearest shearable Sheep with shears. Caller is responsible for positioning
 * within reach (3.5 blocks) — Baritone follow does this. Java side only shears.
 *
 * Why right-click (gameMode.interact) instead of attack: shearing is a vanilla mob-interact that
 * sheep.mobInteract handles when the player's main-hand item is shears. Attacks (KillAura) would
 * kill the sheep. KillAura's default passive-filter ("on" = don't attack passives) lets the player
 * stand next to a sheep with a sword equipped without harming it, so the only thing we have to
 * arrange ourselves is "shears in main hand at moment of interact."
 */
public final class Shearer {
    private Shearer() {}

    private static final double MAX_REACH = 3.5;
    private static final long PER_OP_TIMEOUT_MS = 2000;
    private static final long SELECT_SETTLE_MS = 100;
    private static final long ROT_SETTLE_MS = 50;
    private static final long INTERACT_SETTLE_MS = 300;

    public sealed interface Result permits Ok, Failure {}
    public record Ok(String uuid, String color, int woolDropped) implements Result {}
    public record Failure(String reason, String message) implements Result {}

    public static Result shear() throws InterruptedException {
        Minecraft mc = Minecraft.getInstance();

        SearchOutcome outcome = supply(() -> searchAndPrecheck(mc));
        if (outcome.failure != null) return outcome.failure;

        Sheep sheep = outcome.sheep;
        String color = outcome.color;
        String uuid = outcome.uuid;
        int beforeWool = outcome.woolBefore;

        Failure selectFailure = supply(() -> selectShears(mc));
        if (selectFailure != null) return selectFailure;
        Thread.sleep(SELECT_SETTLE_MS);

        Boolean rotated = supply(() -> { Look.faceEntity(mc, sheep); return mc.player != null; });
        if (rotated == null || !rotated) return new Failure("internal_error", "player vanished during rotate");
        Thread.sleep(ROT_SETTLE_MS);

        supply(() -> { sendInteractPacket(mc, sheep); return null; });
        Thread.sleep(INTERACT_SETTLE_MS);

        return supply(() -> verify(mc, sheep, color, uuid, beforeWool));
    }

    private record SearchOutcome(Sheep sheep, String color, String uuid, int woolBefore, Failure failure) {
        static SearchOutcome ok(Sheep s, String c, String u, int w) {
            return new SearchOutcome(s, c, u, w, null);
        }
        static SearchOutcome fail(Failure f) { return new SearchOutcome(null, null, null, 0, f); }
    }

    private static SearchOutcome searchAndPrecheck(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return SearchOutcome.fail(new Failure("internal_error", "no player (not in world)"));
        ClientLevel level = mc.level;
        if (level == null) return SearchOutcome.fail(new Failure("internal_error", "no level"));
        if (mc.gameMode == null) return SearchOutcome.fail(new Failure("internal_error", "no gameMode"));
        if (p.containerMenu != p.inventoryMenu) {
            return SearchOutcome.fail(new Failure("internal_error",
                "another inventory screen is open; close it before shearing"));
        }

        if (findShearsSlot(p.getInventory().items) < 0) {
            return SearchOutcome.fail(new Failure("no_shears_in_inventory",
                "no shears in inventory — craft shears (2 iron_ingot) first"));
        }

        // Nearest sheep — any sheep, so we can give a precise reason when none are shearable.
        // PREY_SCAN_RADIUS-style horizontal+vertical box; the reach check uses true distance.
        List<net.minecraft.world.entity.Entity> sheepNear =
                Entities.query(p, level, MAX_REACH, MAX_REACH, e -> e instanceof Sheep);
        if (sheepNear.isEmpty()) {
            return SearchOutcome.fail(new Failure("no_sheep_in_reach",
                "no sheep within " + MAX_REACH + " blocks — move closer (e.g. /baritone/follow)"));
        }

        Sheep candidate = null;
        boolean sawBaby = false;
        boolean sawAlreadySheared = false;
        for (var e : sheepNear) {
            Sheep s = (Sheep) e;
            // Distance check excludes false matches at the corners of the AABB query box.
            if (p.distanceTo(s) > MAX_REACH) continue;
            if (s.isBaby()) { sawBaby = true; continue; }
            if (!s.readyForShearing()) { sawAlreadySheared = true; continue; }
            candidate = s;
            break;
        }
        if (candidate == null) {
            if (sawAlreadySheared) {
                return SearchOutcome.fail(new Failure("sheep_already_sheared",
                    "nearest sheep is already sheared — wait for wool to regrow (eat grass)"));
            }
            if (sawBaby) {
                return SearchOutcome.fail(new Failure("sheep_is_baby",
                    "nearest sheep is a baby — wait for it to grow up"));
            }
            return SearchOutcome.fail(new Failure("no_sheep_in_reach",
                "no shearable sheep within " + MAX_REACH + " blocks"));
        }

        // The wool-color drop matches the sheep's wool color. Sheep.getColor() returns a DyeColor.
        String color = candidate.getColor().getName();
        int woolBefore = countWoolItems(p.getInventory().items, color);
        return SearchOutcome.ok(candidate, color, candidate.getUUID().toString(), woolBefore);
    }

    private static Failure selectShears(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return new Failure("internal_error", "no player");
        Inventory inv = p.getInventory();
        int slot = findShearsSlot(inv.items);
        if (slot < 0) return new Failure("no_shears_in_inventory", "shears vanished from inventory");
        if (Inventory.isHotbarSlot(slot)) {
            inv.selected = slot;
        } else {
            // Swap into the currently-selected hotbar slot (same pattern as BedPlacer).
            mc.gameMode.handleInventoryMouseClick(
                InventoryMenu.CONTAINER_ID, slot, inv.selected, ClickType.SWAP, p);
        }
        return null;
    }

    private static void sendInteractPacket(Minecraft mc, Sheep sheep) {
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null) return;
        // mc.gameMode.interact mirrors a vanilla right-click on an entity. It sends the
        // ServerboundInteractPacket and triggers sheep.mobInteract on the server, which performs
        // the shear when the player's main hand holds shears. We do NOT use interactAt here
        // (the EntityHitResult variant) — vanilla sends both for full parity, but the no-hitvec
        // form alone is what server-side actually keys off for the shear action.
        mc.gameMode.interact(p, sheep, InteractionHand.MAIN_HAND);
        p.swing(InteractionHand.MAIN_HAND);
    }

    private static Result verify(Minecraft mc, Sheep sheep, String color, String uuid, int woolBefore) {
        LocalPlayer p = mc.player;
        if (p == null) return new Failure("internal_error", "player vanished mid-op");

        // Two evidence channels: the sheep's own sheared flag flipped, OR a wool delta appeared
        // (covers the edge case where the sheep moved out of tracking range before isSheared could
        // sync back to the client). Either is sufficient.
        boolean shearedFlag = !sheep.isAlive() ? true : sheep.isSheared();
        int woolAfter = countWoolItems(p.getInventory().items, color);
        int delta = Math.max(0, woolAfter - woolBefore);

        if (!shearedFlag && delta == 0) {
            return new Failure("interact_no_effect",
                "right-click sent but sheep still has wool and no drop in inventory — likely server "
                + "rejected (sheep moved out of reach, KillAura killed it, or item not actually shears)");
        }
        return new Ok(uuid, color, delta);
    }

    /** Returns the first inventory slot holding shears, or -1. Scans hotbar + main inv (slots 0..35). */
    private static int findShearsSlot(NonNullList<ItemStack> items) {
        for (int i = 0; i < items.size(); i++) {
            ItemStack s = items.get(i);
            if (s.isEmpty()) continue;
            if (s.getItem() == Items.SHEARS) return i;
        }
        return -1;
    }

    /** Sum across the player's inventory of items matching {@code <color>_wool}. Pure counting. */
    private static int countWoolItems(NonNullList<ItemStack> items, String color) {
        String suffix = color + "_wool";
        int sum = 0;
        for (ItemStack s : items) {
            if (s.isEmpty()) continue;
            String id = Entities.itemId(s);
            int colon = id.indexOf(':');
            String bare = colon < 0 ? id : id.substring(colon + 1);
            if (bare.equals(suffix)) sum += s.getCount();
        }
        return sum;
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
