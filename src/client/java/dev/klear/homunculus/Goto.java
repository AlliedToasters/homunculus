package dev.klear.homunculus;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.event.events.PathEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Synchronous goto utility — drives Baritone's customGoalProcess to a block target and waits for
 * the terminal signal. Used by {@link GotoHandler} (external /baritone/goto) and by
 * {@link CollectSmeltHandler} (internal routing to a registered furnace).
 *
 * <p>Acquires {@link Baritone#SESSION_LOCK} for the duration; returns {@link Failed} with reason
 * {@code busy} if another /baritone/* call is in flight. The lock is released before returning.
 *
 * <p>On any non-arrived terminal, calls {@code pathingBehavior.cancelEverything()} before
 * releasing the lock — callers can assume Baritone is idle when this returns.
 */
public final class Goto {
	private Goto() {}

	public static final long HARD_CAP_SECONDS = 300;
	private static final long GAME_THREAD_TIMEOUT_MS = 5_000;

	public sealed interface Outcome permits Arrived, Failed {
		double[] finalPosition();
	}

	public record Arrived(String message, double[] finalPosition) implements Outcome {}

	public record Failed(String reason, String message, double[] finalPosition) implements Outcome {}

	public static Outcome run(int x, int y, int z, long timeoutSeconds, long arrivalTolerance) {
		if (!Baritone.isApiLoaded()) {
			return new Failed("baritone_not_loaded", "Baritone API not present at runtime", null);
		}

		if (!Baritone.SESSION_LOCK.tryLock()) {
			return new Failed("busy", "another /baritone/* call is in flight", null);
		}

		try {
			return runLocked(x, y, z, timeoutSeconds, arrivalTolerance);
		} finally {
			Baritone.SESSION_LOCK.unlock();
		}
	}

	private static Outcome runLocked(int x, int y, int z, long timeoutSeconds, long arrivalTolerance) {
		LinkedBlockingQueue<Signal> queue = new LinkedBlockingQueue<>();
		AtomicBoolean closed = new AtomicBoolean(false);

		AbstractGameEventListener listener = new AbstractGameEventListener() {
			@Override public void onPathEvent(PathEvent event) {
				if (!closed.get()) queue.offer(new PathSignal(event));
			}
			@Override public void onTick(TickEvent event) {
				if (closed.get()) return;
				IBaritone bar = BaritoneAPI.getProvider().getPrimaryBaritone();
				LocalPlayer p = Minecraft.getInstance().player;
				if (bar == null || p == null) return;
				queue.offer(new TickSignal(
						bar.getCustomGoalProcess().isActive(),
						bar.getPathingBehavior().isPathing(),
						p.getX(), p.getY(), p.getZ()));
			}
		};

		double[] initialPos;
		try {
			initialPos = ClientThread.supply(() -> {
				IBaritone bar = BaritoneAPI.getProvider().getPrimaryBaritone();
				if (bar == null) throw new IllegalStateException("no primary Baritone (player not in world?)");
				LocalPlayer p = Minecraft.getInstance().player;
				if (p == null) throw new IllegalStateException("no player");
				bar.getGameEventHandler().registerEventListener(listener);
				bar.getCustomGoalProcess().setGoalAndPath(new GoalBlock(x, y, z));
				return new double[] { p.getX(), p.getY(), p.getZ() };
			}).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			closed.set(true);
			return new Failed("internal_error", "failed to start goto: " + rootMessage(e), null);
		}

		long timeoutMs = Math.min(timeoutSeconds, HARD_CAP_SECONDS) * 1000L;
		long deadline = System.currentTimeMillis() + timeoutMs;

		double[] lastPos = initialPos;
		try {
			while (true) {
				long wait = deadline - System.currentTimeMillis();
				if (wait <= 0) {
					return new Failed("timeout", "deadline elapsed without arrival", lastPos);
				}
				Signal sig = queue.poll(wait, TimeUnit.MILLISECONDS);
				if (sig == null) continue;

				if (sig instanceof TickSignal t) {
					lastPos = new double[] { t.x, t.y, t.z };
					double dist = manhattan(lastPos, x, y, z);
					if (dist <= arrivalTolerance) {
						return new Arrived("arrived at target within tolerance " + arrivalTolerance, lastPos);
					}
					if (!t.active && !t.pathing) {
						return new Failed("stuck",
								"Baritone idled with " + String.format("%.2f", dist) + " blocks remaining", lastPos);
					}
				} else if (sig instanceof PathSignal p) {
					switch (p.event) {
						case CALC_FAILED -> {
							return new Failed("unreachable", "Baritone reported PathEvent.CALC_FAILED", lastPos);
						}
						case AT_GOAL -> {
							return new Arrived("Baritone reported PathEvent.AT_GOAL", lastPos);
						}
						case CANCELED -> {
							return new Failed("canceled", "Baritone reported PathEvent.CANCELED", lastPos);
						}
						default -> {}
					}
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Failed("internal_error", "interrupted", lastPos);
		} finally {
			closed.set(true);
			try {
				ClientThread.supply(() -> {
					IBaritone bar = BaritoneAPI.getProvider().getPrimaryBaritone();
					if (bar != null) bar.getPathingBehavior().cancelEverything();
					return null;
				}).get(GAME_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
			} catch (Exception e) {
				HomunculusClient.LOGGER.warn("Goto cleanup cancelEverything threw", e);
			}
		}
	}

	private static double manhattan(double[] pos, int tx, int ty, int tz) {
		return Math.abs(pos[0] - tx) + Math.abs(pos[1] - ty) + Math.abs(pos[2] - tz);
	}

	private static String rootMessage(Throwable t) {
		Throwable c = t.getCause() != null ? t.getCause() : t;
		String m = c.getMessage();
		return m == null ? c.getClass().getSimpleName() : m;
	}

	private sealed interface Signal {}
	private record TickSignal(boolean active, boolean pathing, double x, double y, double z) implements Signal {}
	private record PathSignal(PathEvent event) implements Signal {}
}
