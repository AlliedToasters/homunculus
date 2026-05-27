package dev.toast.homunculus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Right-clicks the nearest bed within range to put the player to sleep.
 *
 * Does NOT move the player — that's the agent's job. Returns too_far_from_bed if the nearest
 * bed is more than 3.5 blocks away. Returns immediately after the sleep packet; does not block
 * waiting for dawn. The agent polls GET /stats is_sleeping to wait for wake.
 *
 * If the player is holding a bed item, we switch to a non-bed hotbar slot first — right-clicking
 * a bed while holding another bed tries to place the held bed rather than interact with the bed.
 */
public final class Sleeper {
    private Sleeper() {}

    private static final long PER_OP_TIMEOUT_MS = 2000;
    private static final long INTERACT_SETTLE_MS = 200;

    public sealed interface Result permits Ok, Failure {}
    public record Ok(int x, int y, int z, long dayTicksAtSleep) implements Result {}
    public record Failure(String reason, String message) implements Result {}

    public static Result sleep(int maxRadius) throws InterruptedException {
        Minecraft mc = Minecraft.getInstance();

        SleepCheck check = supply(() -> preconditionCheck(mc, maxRadius));
        if (check.failure != null) return check.failure;

        BlockPos bedPos = check.bedPos;
        long dayTicks = check.dayTicks;

        supply(() -> { ensureNotHoldingBed(mc); return null; });
        Thread.sleep(50);

        supply(() -> { Look.faceBlockTop(mc, bedPos); return null; });
        Thread.sleep(50);

        supply(() -> { sendSleepPacket(mc, bedPos); return null; });
        Thread.sleep(INTERACT_SETTLE_MS);

        Boolean sleeping = supply(() -> {
            LocalPlayer p = mc.player;
            return p != null && p.isSleeping();
        });

        if (Boolean.TRUE.equals(sleeping)) {
            return new Ok(bedPos.getX(), bedPos.getY(), bedPos.getZ(), dayTicks);
        }

        return supply(() -> diagnoseFailure(mc, bedPos));
    }

    private record SleepCheck(BlockPos bedPos, long dayTicks, Failure failure) {
        static SleepCheck ok(BlockPos pos, long ticks) { return new SleepCheck(pos, ticks, null); }
        static SleepCheck fail(Failure f) { return new SleepCheck(null, 0, f); }
    }

    private static SleepCheck preconditionCheck(Minecraft mc, int maxRadius) {
        LocalPlayer p = mc.player;
        if (p == null) return SleepCheck.fail(new Failure("internal_error", "no player (not in world)"));
        ClientLevel level = mc.level;
        if (level == null) return SleepCheck.fail(new Failure("internal_error", "no level"));

        if (p.isSleeping()) return SleepCheck.fail(new Failure("already_sleeping", "player is already sleeping"));

        BlockPos origin = p.blockPosition();
        BlockPos nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        int r = maxRadius;
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    if (!(level.getBlockState(pos).getBlock() instanceof BedBlock)) continue;
                    double d = origin.distSqr(pos);
                    if (d < nearestDistSq) {
                        nearestDistSq = d;
                        nearest = pos;
                    }
                }
            }
        }
        if (nearest == null) {
            return SleepCheck.fail(new Failure("no_bed_nearby",
                "no bed found within " + maxRadius + " blocks"));
        }

        Vec3 bedCenter = Vec3.atCenterOf(nearest);
        double dist = p.position().distanceTo(bedCenter);
        if (dist > 3.5) {
            return SleepCheck.fail(new Failure("too_far_from_bed",
                String.format(java.util.Locale.ROOT, "closest bed is %.1f blocks away (need ≤ 3)", dist)));
        }

        long worldTime = level.getDayTime();
        long dayTicks = worldTime % 24000L;
        boolean isThundering = level.isThundering();
        boolean isNight = (dayTicks >= 12541 && dayTicks <= 23458) || isThundering;
        if (!isNight) {
            return SleepCheck.fail(new Failure("not_night",
                "cannot sleep during day (day_ticks=" + dayTicks + ", thundering=" + isThundering + ")"));
        }

        // Match MC's Player.startSleepInBed monster check: player-centered ±8 horizontal / ±5
        // vertical box, hostility by Monster.class (Entities.isMonsterClass — the same definition
        // vanilla uses here). We can't apply MC's server-side isPreventingPlayerRest filter, so we
        // include all such mobs — false positives at worst.
        if (Entities.count(p, level, 8, 5, Entities::isMonsterClass) > 0) {
            return SleepCheck.fail(new Failure("monsters_nearby",
                "hostile mob(s) within 8 blocks horizontally / 5 vertically"));
        }

        return SleepCheck.ok(nearest, dayTicks);
    }

    private static void ensureNotHoldingBed(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        if (p.getMainHandItem().isEmpty()) return;
        if (!(p.getMainHandItem().getItem() instanceof BlockItem bi) || !(bi.getBlock() instanceof BedBlock)) return;
        for (int i = 0; i < 9; i++) {
            var s = p.getInventory().items.get(i);
            boolean isBed = !s.isEmpty() && (s.getItem() instanceof BlockItem b2) && (b2.getBlock() instanceof BedBlock);
            if (!isBed) {
                p.getInventory().selected = i;
                return;
            }
        }
    }

    private static void sendSleepPacket(Minecraft mc, BlockPos bedPos) {
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null) return;
        Vec3 hitVec = Vec3.atCenterOf(bedPos);
        BlockHitResult bhit = new BlockHitResult(hitVec, Direction.UP, bedPos, false);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, bhit);
    }

    private static Failure diagnoseFailure(Minecraft mc, BlockPos bedPos) {
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null) return new Failure("internal_error", "player or level vanished");

        if (!(level.getBlockState(bedPos).getBlock() instanceof BedBlock)) {
            return new Failure("internal_error", "bed block no longer present at " + bedPos);
        }

        long dayTicks = level.getDayTime() % 24000L;
        boolean isThundering = level.isThundering();
        if ((dayTicks < 12541 || dayTicks > 23458) && !isThundering) {
            return new Failure("not_night", "it is not night (day_ticks=" + dayTicks + ")");
        }

        if (Entities.count(p, level, 8, 5, Entities::isMonsterClass) > 0) {
            return new Failure("monsters_nearby", "hostile mob(s) within 8 blocks horizontally / 5 vertically");
        }

        BlockPos above = bedPos.above();
        if (!level.getBlockState(above).isAir()) {
            return new Failure("bed_obstructed", "block above bed is not air");
        }

        return new Failure("internal_error", "server rejected sleep for unknown reason");
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
