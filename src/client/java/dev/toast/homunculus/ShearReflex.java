package dev.toast.homunculus;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Ambient shear reflex — the right-click analogue of Wurst's KillAura. Every client tick,
 * if the player is holding shears in main hand and a shearable Sheep is within 3.5 blocks,
 * fires an interact packet on it (vanilla {@code sheep.mobInteract} handles the actual shear).
 *
 * <p>Design contrast with {@link Shearer} (the HTTP path): Shearer does the search +
 * <i>auto-swap shears into main hand</i> + face + interact + verify. The reflex deliberately
 * does NONE of those — no auto-swap (would yank pickaxe out mid-mine), no aim (camera stays
 * on the agent's other activity), no verify (the next tick re-evaluates; if the sheep got
 * sheared it'll fail readyForShearing and we no-op). One entity scan + one packet send per tick.
 *
 * <p>Composes with KillAura: KillAura attacks (left-click), ShearReflex shears (right-click),
 * both can fire on the same target. With KillAura's "Filter passive mobs" default flipped to
 * false (attack passives) at preflight, a walk-past a sheep with shears in hand = shear drop
 * (1-3 wool) + kill drop (1 wool + mutton). The explicit shear_sheep tool temporarily flips
 * the filter back ON so the sheep survives — keeps the wool renewable.
 *
 * <p>Tick cost is bounded: the {@code main_hand == SHEARS} gate is one item-id comparison;
 * only when shears are held do we run the entity query. So 99%+ of ticks no-op cheaply.
 */
public final class ShearReflex {
    private ShearReflex() {}

    private static final double MAX_REACH = 3.5;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ShearReflex::onTick);
    }

    private static void onTick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        ClientLevel level = mc.level;
        if (level == null || mc.gameMode == null) return;
        // Cheap early-out — only do entity work when shears are actually in main hand.
        if (p.getMainHandItem().getItem() != Items.SHEARS) return;

        Sheep target = nearestShearable(p, level);
        if (target == null) return;

        // Fire the right-click. No aim (mc.gameMode.interact is packet-level, doesn't need
        // the player to be looking at the target). No swing animation either — the reflex is
        // supposed to be ambient, not draw the eye. Server applies sheep.mobInteract; if
        // shears are in hand, the shear runs and wool drops.
        mc.gameMode.interact(p, target, InteractionHand.MAIN_HAND);
    }

    private static Sheep nearestShearable(LocalPlayer p, ClientLevel level) {
        List<Entity> nearby = Entities.query(p, level, MAX_REACH, e -> e instanceof Sheep);
        for (Entity e : nearby) {
            Sheep s = (Sheep) e;
            // AABB query box can exceed true distance at corners; cull.
            if (p.distanceTo(s) > MAX_REACH) continue;
            if (s.isBaby()) continue;
            if (!s.readyForShearing()) continue;
            return s;
        }
        return null;
    }
}
