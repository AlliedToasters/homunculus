package dev.toast.homunculus;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Tick-locked block breaker — the "legit break" engine.
 *
 * <h2>Why this exists (the load-bearing insight)</h2>
 *
 * <p>Minecraft survival block-breaking is a tick-counted HANDSHAKE between client
 * and server, not a fire-and-forget action. The vanilla client drives it from
 * exactly ONE place: {@code Minecraft.tick()} → {@code continueAttack()} calls
 * {@link MultiPlayerGameMode#continueDestroyBlock} ONCE PER CLIENT GAME TICK while
 * left-click is held. Each call adds the per-tick fraction
 * {@code BlockState.getDestroyProgress} (= 1/ticksToBreak) to a {@code destroyProgress}
 * accumulator; after exactly {@code ticksToBreak} ticks it crosses 1.0, the client
 * sends STOP_DESTROY_BLOCK and removes the block locally. The SERVER runs its own
 * copy of that per-tick simulation between the START and STOP packets and validates
 * the elapsed TICK COUNT. Both sides advance one increment per game tick, so they
 * agree and the break is accepted.
 *
 * <p>If instead you pump {@code continueDestroyBlock} from an OFF-THREAD caller (the
 * HTTP worker via {@code ClientThread.supply()} = {@code Minecraft.submit()}) on a
 * wall-clock cadence, the submitted task is drained in the client's render/runTick
 * pump — which fires MANY times per game tick and is decoupled from the tick
 * counter. {@code destroyProgress} then reaches 1.0 in fewer GAME ticks than the
 * block needs; the client emits STOP "too fast"; from the server's tick-counted view
 * that is an INSTAMINE, which it REJECTS — it drops the break and re-sends the block
 * state (the block reappears, no item drop). That was the bamboo harvester's
 * {@code have 0} / "client shows air but the block is still there server-side"
 * symptom.
 *
 * <p>Approximating the tick from off-thread — e.g. gating the off-thread loop on
 * {@code level.getGameTime()} — does NOT fix it: the read-time and break calls are
 * separate {@code supply()} round-trips, the break still executes off the tick
 * boundary, and more than one break can land inside a single real game tick. The
 * ONLY way to mirror the server's mine-time is to drive the break FROM THE REAL TICK
 * LOOP, one {@code continueDestroyBlock} per tick — which is what this engine does.
 *
 * <h2>Model</h2>
 *
 * <p>A single active {@link Job} at a time. Off-thread callers {@link #submit} a job
 * and {@link #awaitDone block-poll} its terminal state; the break math (start,
 * continue, swing, face) never leaves the {@link ClientTickEvents#END_CLIENT_TICK}
 * thread, and advances by at most one {@code continueDestroyBlock} per tick — the
 * same cadence as a player holding left-click. Instant-break blocks (e.g. bamboo
 * with a sword) finish on the START tick in a single packet; gradual breaks accrue
 * tick-by-tick and the server accepts them because the tick count now matches.
 *
 * <p>Single-slot: a new {@link #submit} replaces any in-flight job. Callers
 * serialize themselves (the bamboo harvester breaks one base at a time, awaiting
 * each before submitting the next); the Python channel further serializes per agent.
 */
public final class TickBreaker {
	public enum State { PENDING, BREAKING, BROKE, GONE, TIMED_OUT, NO_WORLD }

	/** A single break request. State is advanced on the tick thread, read off-thread. */
	public static final class Job {
		final BlockPos pos;
		final Direction face;
		final int maxTicks;
		volatile State state = State.PENDING;
		volatile int ticks = 0;
		volatile String startDebug = "";
		private boolean started = false;

		Job(BlockPos pos, Direction face, int maxTicks) {
			this.pos = pos.immutable();
			this.face = face;
			this.maxTicks = maxTicks;
		}

		public State state() { return state; }

		public int ticks() { return ticks; }

		public boolean isTerminal() {
			return state != State.PENDING && state != State.BREAKING;
		}
	}

	public static final TickBreaker INSTANCE = new TickBreaker();

	private final AtomicReference<Job> active = new AtomicReference<>();

	/** Diagnostic crumb from the most recent job (start path + terminal). */
	public volatile String lastDebug = "";

	private TickBreaker() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(INSTANCE::onTick);
	}

	/** Enqueue a break for {@code pos}. Replaces any in-flight job. */
	public Job submit(BlockPos pos, Direction face, int maxTicks) {
		Job job = new Job(pos, face, maxTicks);
		active.set(job);
		return job;
	}

	/**
	 * Block (off-thread) until the job reaches a terminal state or the wall-clock
	 * safety timeout elapses. {@code maxTicks} (game ticks) is the real bound — this
	 * wall-clock cap only guards against a stalled tick loop (e.g. paused client) and
	 * should be set well above {@code maxTicks} at the slowest expected TPS. Reads
	 * volatile state only; does no game-state access.
	 */
	public State awaitDone(Job job, long timeoutMs, long pollMs) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (!job.isTerminal()) {
			if (System.currentTimeMillis() > deadline) {
				active.compareAndSet(job, null);
				return State.TIMED_OUT;
			}
			Thread.sleep(pollMs);
		}
		active.compareAndSet(job, null);
		return job.state;
	}

	/* ─────────────────────── the lockstep (client tick thread) ────────────────────── */

	private void onTick(Minecraft mc) {
		Job job = active.get();
		if (job == null || job.isTerminal()) return;

		LocalPlayer player = mc.player;
		MultiPlayerGameMode gm = mc.gameMode;
		if (player == null || gm == null || mc.level == null) {
			job.state = State.NO_WORLD;
			return;
		}

		// Gone already? (a neighbour column's cascade, or instabreak last tick).
		if (mc.level.getBlockState(job.pos).isAir()) {
			job.state = job.started ? State.BROKE : State.GONE;
			return;
		}

		// Keep facing the target every tick — cheap, and matches a player who holds
		// their aim on the block through the break.
		Look.faceBlockTop(mc, job.pos);

		if (!job.started) {
			// First tick: START. Instant-break blocks (sword vs bamboo, creative)
			// break right here in a single packet — startDestroyBlock destroys them
			// internally, so the cell is air on this same tick.
			float perTick = mc.level.getBlockState(job.pos)
					.getDestroyProgress(player, player.level(), job.pos);
			gm.startDestroyBlock(job.pos, job.face);
			player.swing(InteractionHand.MAIN_HAND);
			job.started = true;
			boolean instabroke = mc.level.getBlockState(job.pos).isAir();
			job.startDebug = String.format(
					"start: perTick=%.4f (ticksToBreak~%.1f) instabrokeClient=%b onGround=%b inWater=%b held=%s",
					perTick, perTick > 0 ? 1.0f / perTick : -1f, instabroke,
					player.onGround(), player.isInWater(),
					player.getMainHandItem().isEmpty() ? "empty"
							: player.getMainHandItem().getItem().toString());
			HomunculusClient.LOGGER.info("[TickBreaker] {}", job.startDebug);
			if (instabroke) {
				job.state = State.BROKE;
				finishDebug(job, "BROKE_ON_START");
				return;
			}
			job.state = State.BREAKING;
		} else {
			// Subsequent ticks: EXACTLY ONE continue per real game tick — the lockstep
			// with the server's own per-tick destroy simulation.
			//
			// CRUCIAL: continueDestroyBlock's boolean return is NOT a "block broke"
			// signal. It returns true for "destroy is proceeding" (normal in-progress
			// AND the post-break destroyDelay cooldown); it returns false only when
			// the target is gone/changed. The block has ACTUALLY broken when its cell
			// goes air — the client accumulates destroyProgress per call and, once it
			// crosses 1.0, internally calls destroyBlock() (removes the block locally +
			// sends STOP_DESTROY_BLOCK). So we ignore the return and terminate solely
			// on isAir(). (Reading the boolean as completion was the real bug: it made
			// the loop stop after ONE continue — progress ~0.033 of 1.0 — sending no
			// real break, so the block stayed on both client and server.)
			gm.continueDestroyBlock(job.pos, job.face);
			player.swing(InteractionHand.MAIN_HAND);
			if (mc.level.getBlockState(job.pos).isAir()) {
				job.state = State.BROKE;
				finishDebug(job, "BROKE_GRADUAL");
				return;
			}
		}

		job.ticks++;
		if (job.ticks >= job.maxTicks) {
			job.state = State.TIMED_OUT;
			finishDebug(job, "TIMED_OUT");
		}
	}

	private void finishDebug(Job job, String how) {
		lastDebug = job.startDebug + " | end: " + how + " ticks=" + job.ticks;
		HomunculusClient.LOGGER.info("[TickBreaker] {}", lastDebug);
	}
}
