package dev.toast.homunculus;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Proximity-driven hotbar swap to shears. The "AutoSword for shears" — when a shearable
 * sheep enters {@link #SWAP_RADIUS} and the player isn't already holding shears, save the
 * currently selected hotbar slot and swap to whichever hotbar slot holds shears (slot 4 by
 * Equipper's convention, but any hotbar position works as fallback). When no shearable
 * sheep has been seen for {@link #UNSWAP_HYSTERESIS_TICKS} ticks, restore the prior slot.
 *
 * <p>Layered with {@link ShearReflex}: AutoShears swaps at 6m (gives the swap a tick or
 * two to settle before the sheep is in shear range); ShearReflex fires the actual shear
 * interact at 3.5m. Net effect: walking past a sheep with shears anywhere in the hotbar
 * + KillAura (passive filter off, default) = wool drop + kill drop + meat, no tool call.
 *
 * <p>Race with Wurst's AutoSword (which swaps to sword on KillAura attack): both can fire
 * each tick. They alternate — one tick the player holds a sword (sword hit lands), next
 * tick the player holds shears (shear interact lands), and so on until the sheep is both
 * sheared and dead. Tested intent: the substrate captures both effects from a single
 * encounter without the agent calling either tool.
 *
 * <p>Hysteresis exists to prevent flapping when a sheep walks in and out of the radius
 * each tick (rare with NoAI test sheep; common with wild ones meandering at the edge of
 * the box). 20 ticks (~1s) is short enough that the agent isn't stuck with shears
 * long after the encounter ends.
 */
public final class AutoShears {
    private AutoShears() {}

    private static final double SWAP_RADIUS = 6.0;
    private static final long UNSWAP_HYSTERESIS_TICKS = 20;

    // State across ticks. priorSelected = -1 means "not currently swapped".
    // We restore to priorSelected once no shearable sheep has been seen for the
    // hysteresis window. The "shears in hand on entry — no swap to make" case
    // keeps priorSelected at -1 so the no-op tick path stays cheap.
    private static int priorSelected = -1;
    private static long lastSheepSeenTick = -1;
    private static long tickCounter = 0L;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(AutoShears::onTick);
    }

    private static void onTick(Minecraft mc) {
        tickCounter++;
        LocalPlayer p = mc.player;
        if (p == null) {
            // Lost player (login screen, dimension change, death cinematic).
            // Clear state so a fresh world doesn't inherit a stale priorSelected.
            priorSelected = -1;
            lastSheepSeenTick = -1;
            return;
        }
        ClientLevel level = mc.level;
        if (level == null) return;

        // Find first hotbar slot holding shears. Prefer slot 4 (Equipper convention)
        // but accept any hotbar slot — a player who manually shuffled shears elsewhere
        // should still get the reflex. Main inv (slots 9-35) is intentionally excluded:
        // setCarriedItem only addresses hotbar.
        int shearsSlot = findShearsHotbarSlot(p.getInventory());
        if (shearsSlot < 0) {
            // No shears in hotbar at all. If we were swapped, the user must have moved
            // them — abandon prior-slot tracking to avoid restoring to something stale.
            priorSelected = -1;
            return;
        }

        boolean sheepNear = hasShearableSheepNear(p, level);
        if (sheepNear) {
            lastSheepSeenTick = tickCounter;
        }

        Inventory inv = p.getInventory();
        int selected = inv.selected;

        if (sheepNear && selected != shearsSlot) {
            // Swap. Save prior slot only on first swap of this encounter so a same-
            // encounter re-swap (sheep oscillating near radius edge) doesn't overwrite
            // the original-prior with shearsSlot.
            if (priorSelected < 0) priorSelected = selected;
            inv.selected = shearsSlot;
            p.connection.send(new ServerboundSetCarriedItemPacket(shearsSlot));
            return;
        }

        if (!sheepNear && priorSelected >= 0) {
            // Hysteresis: only restore once we've had a quiet streak. Keeps slot stable
            // when sheep walks in and out of the swap box during one encounter.
            if (tickCounter - lastSheepSeenTick < UNSWAP_HYSTERESIS_TICKS) return;
            // Only restore if the player is still on shears (i.e., nothing else has
            // grabbed the slot). If they manually selected something else mid-window,
            // we don't second-guess them — just clear our state.
            if (selected == shearsSlot) {
                inv.selected = priorSelected;
                p.connection.send(new ServerboundSetCarriedItemPacket(priorSelected));
            }
            priorSelected = -1;
        }
    }

    private static int findShearsHotbarSlot(Inventory inv) {
        // Slot 4 is Equipper's staging slot; check it first.
        if (!inv.items.get(4).isEmpty() && inv.items.get(4).getItem() == Items.SHEARS) {
            return 4;
        }
        for (int i = 0; i < 9; i++) {
            if (i == 4) continue;
            ItemStack s = inv.items.get(i);
            if (!s.isEmpty() && s.getItem() == Items.SHEARS) return i;
        }
        return -1;
    }

    private static boolean hasShearableSheepNear(LocalPlayer p, ClientLevel level) {
        List<Entity> nearby = Entities.query(p, level, SWAP_RADIUS, e -> e instanceof Sheep);
        for (Entity e : nearby) {
            Sheep s = (Sheep) e;
            if (p.distanceTo(s) > SWAP_RADIUS) continue;
            if (s.isBaby()) continue;
            if (!s.readyForShearing()) continue;
            return true;
        }
        return false;
    }
}
