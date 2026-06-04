package dev.toast.homunculus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Direct bamboo harvester — the substrate workaround for Baritone bug #4653.
 *
 * <p>Bamboo grows as 1-wide columns whose segments sit at EYE level with standing
 * space above; that is exactly Baritone MineProcess's stuck-on-eye-level case
 * (#4653 — it completes the mine goal mid-jump and deactivates with nothing
 * harvested). Baritone also can't /excavate dense bamboo (no room for a breaking
 * stance) nor path through it. So we bypass Baritone entirely.
 *
 * <p>Algorithm (the load-bearing insight: break the BASE):
 * a bamboo column's base block sits at GROUND level — not eye level, so no #4653
 * — and breaking the base drops the ENTIRE column (the unsupported segments above
 * pop server-side), i.e. one break = a whole stalk's yield. We find each base
 * within block-break reach and break it through {@link TickBreaker} — the
 * tick-locked "legit break" engine (see its javadoc for WHY: a survival break is a
 * tick-counted handshake the server validates, so the {@code continueDestroyBlock}
 * pump MUST run once per real game tick or the server rejects it as an instamine).
 * Barehanded that takes ~30 ticks; a held sword instabreaks on the START tick. The
 * player stays put, so the cascade's drops vacuum in via normal item pickup — the
 * collection step Baritone-at-a-distance skipped.
 *
 * <p>This only harvests bamboo within reach of the player's CURRENT position;
 * traversal across a grove is the caller's job (the cleared bubble makes a short
 * Baritone hop pathable afterward).
 */
public final class HarvestBamboo {
	private HarvestBamboo() {}

	/** Break reach kept short so the cascade's drops land within ~item-pickup range
	 *  of the stationary player (1.21 interaction range is 4.5; pickup is ~1.5). */
	private static final double MAX_REACH = 3.0;
	private static final long ROT_SETTLE_MS = 60;
	/** Barehanded bamboo ≈ 30 ticks; cap a touch above so a slow break still completes.
	 *  This is the real (game-tick) bound — TickBreaker counts genuine ticks. */
	private static final int MAX_TICKS_PER_BASE = 50;
	/** Off-thread poll cadence while awaiting a TickBreaker job's terminal state. */
	private static final long POLL_MS = 10;
	/** Wall-clock safety net for one base: well above MAX_TICKS_PER_BASE even at the
	 *  slow TPS of the headless box (50 ticks @ ~10 TPS = 5s). */
	private static final long PER_BASE_TIMEOUT_MS = 10_000;
	/** Let the column cascade's item drops settle + vacuum into the stationary player. */
	private static final long DRAIN_MS = 700;
	private static final long PER_OP_TIMEOUT_MS = 5000;
	private static final int MAX_RADIUS = 8;

	public sealed interface Result permits Ok, Failure {}

	public record Ok(int columnsBroken, int basesAttempted, int basesInReach) implements Result {}

	public record Failure(String reason, String message) implements Result {}

	public static Result harvest(int radius) throws InterruptedException {
		Minecraft mc = Minecraft.getInstance();

		// Tool selection is now an OPTIMISATION, not a correctness requirement: the
		// tick-locked break (TickBreaker) makes barehanded/pickaxe gradual breaks
		// land server-side too, because the continueDestroyBlock pump runs once per
		// real game tick and the server's tick count agrees. A held sword still
		// instabreaks bamboo on the START tick (~1 tick vs ~30), so prefer one if the
		// hotbar has it.
		supply(() -> {
			trySelectSword(mc);
			return Boolean.TRUE;
		});
		Thread.sleep(ROT_SETTLE_MS);

		List<BlockPos> bases = supply(() -> findBasesInReach(mc, radius));
		if (bases == null) {
			return new Failure("internal_error", "no player/level (not in world)");
		}
		int inReach = bases.size();
		if (inReach == 0) {
			return new Failure("no_bamboo",
					"no bamboo base within reach (" + MAX_REACH + " blocks) — move closer");
		}

		int broken = 0;
		int attempted = 0;
		for (BlockPos base : bases) {
			attempted++;
			// A neighbour column's cascade may have already removed this one.
			Boolean still = supply(() -> isBamboo(mc, base));
			if (still == null || !still) {
				broken++;
				continue;
			}
			// Break the base through the tick-locked engine: it pumps
			// continueDestroyBlock once per REAL game tick (faces + swings on the tick
			// thread), so the server's tick count agrees and the break lands —
			// barehanded or with a sword (sword instabreaks on the START tick). See
			// TickBreaker for why off-thread pumping was rejected as an instamine.
			TickBreaker.Job job = TickBreaker.INSTANCE.submit(base, Direction.UP, MAX_TICKS_PER_BASE);
			TickBreaker.State st = TickBreaker.INSTANCE.awaitDone(job, PER_BASE_TIMEOUT_MS, POLL_MS);
			if (st == TickBreaker.State.BROKE || st == TickBreaker.State.GONE) {
				broken++;
			}
		}
		Thread.sleep(DRAIN_MS); // collect the cascade's drops (player adjacent → auto pickup)
		return new Ok(broken, attempted, inReach);
	}

	/** Bamboo base blocks (bamboo whose block-below is NOT bamboo) within break reach,
	 *  nearest-first. Runs on the client thread. */
	private static List<BlockPos> findBasesInReach(Minecraft mc, int radius) {
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (p == null || level == null) return null;
		Vec3 eye = p.getEyePosition();
		BlockPos center = p.blockPosition();
		int r = Math.max(1, Math.min(radius, MAX_RADIUS));
		List<BlockPos> out = new ArrayList<>();
		BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					cur.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					if (level.getBlockState(cur).getBlock() != Blocks.BAMBOO) continue;
					if (level.getBlockState(cur.below()).getBlock() == Blocks.BAMBOO) continue;
					if (reachSqr(eye, cur) <= MAX_REACH * MAX_REACH) {
						out.add(cur.immutable());
					}
				}
			}
		}
		out.sort(Comparator.comparingDouble(b -> reachSqr(eye, b)));
		return out;
	}

	private static double reachSqr(Vec3 eye, BlockPos b) {
		double cx = b.getX() + 0.5, cy = b.getY() + 0.5, cz = b.getZ() + 0.5;
		return (cx - eye.x) * (cx - eye.x) + (cy - eye.y) * (cy - eye.y) + (cz - eye.z) * (cz - eye.z);
	}

	/** Select a hotbar sword as the held item (swords instant-break bamboo). No-op
	 *  if one is already selected or none is in the hotbar. Runs on the client thread. */
	private static void trySelectSword(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null) return;
		Inventory inv = p.getInventory();
		if (isSword(inv.getItem(inv.selected))) return;
		for (int i = 0; i < 9; i++) {  // hotbar slots
			if (isSword(inv.getItem(i))) {
				inv.selected = i;
				if (p.connection != null) {
					p.connection.send(new ServerboundSetCarriedItemPacket(i));
				}
				return;
			}
		}
	}

	private static boolean isSword(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().endsWith("_sword");
	}

	private static boolean isBamboo(Minecraft mc, BlockPos pos) {
		ClientLevel level = mc.level;
		return level != null && level.getBlockState(pos).getBlock() == Blocks.BAMBOO;
	}

	private static <T> T supply(Supplier<T> task) {
		try {
			return ClientThread.supply(task).get(PER_OP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			throw new RuntimeException("client-thread op timed out", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("interrupted", e);
		} catch (ExecutionException e) {
			Throwable c = e.getCause();
			if (c instanceof RuntimeException re) throw re;
			throw new RuntimeException(c);
		}
	}
}
