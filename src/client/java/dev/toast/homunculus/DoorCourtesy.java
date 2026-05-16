package dev.toast.homunculus;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Auto-closes doors and fence gates after the player crosses through them — the agent
 * equivalent of "close the door behind you."
 *
 * <p>Each tick we scan a 5×4×5 box around the player for doors/gates and diff against
 * last tick's snapshot. A closed→open transition pushes one entry onto a small queue
 * recording the player's side of the door at open-time. Each entry is checked against
 * the current player position; when the player has crossed to the other side and is
 * clear of the door's footprint, we send a use-block packet to close it.
 *
 * <p>State is bounded: {@code lastSeenOpen} caches scan-box cells only (rebuilds each
 * tick, evicting cells outside the box). {@code queue} holds only doors opened while
 * we were nearby and not yet crossed — typically empty, rarely more than 2 entries.
 * Per-tick cost is one tiny scan plus a queue walk.
 *
 * <p>Iron doors are skipped (they don't open by hand). Trapdoors are out of scope.
 *
 * <p><b>Instrumentation:</b> all state changes append to a 256-entry ring buffer
 * accessible via {@code GET /debug/door_courtesy}. Each tick's events are tagged with
 * a monotonic tick counter so traces can be reconstructed unambiguously.
 */
public final class DoorCourtesy {
	private DoorCourtesy() {}

	private static final int SCAN_R = 2;        // +/- cells on x/z
	private static final int SCAN_Y_MIN = -1;   // relative to player feet
	private static final int SCAN_Y_MAX = 2;
	private static final double CROSS_BUFFER = 0.7;  // blocks past door center before we close
	private static final long TTL_MS = 30_000L;

	private static final int EVENT_BUFFER_SIZE = 256;
	private static final long WATCHLIST_TTL_MS = 120_000L;

	private static final Object LOCK = new Object();
	private static final Map<BlockPos, Boolean> lastSeenOpen = new HashMap<>();
	private static final Map<BlockPos, Entry> queue = new HashMap<>();
	private static final Map<BlockPos, Long> postCloseWatchlist = new HashMap<>();
	private static final Deque<Event> events = new ArrayDeque<>();
	private static long tickCounter = 0L;

	private record Entry(BlockPos pos, Direction.Axis crossAxis, double crossCoord,
	                     int sideAtOpen, long openedAtMs) {}

	public record Event(long tick, long ts, String type, BlockPos doorPos,
	                    double playerX, double playerY, double playerZ, String detail) {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(DoorCourtesy::onTick);
	}

	private static void onTick(Minecraft mc) {
		synchronized (LOCK) {
			tickCounter++;

			ClientLevel level = mc.level;
			LocalPlayer player = mc.player;
			if (level == null || player == null) {
				if (!lastSeenOpen.isEmpty()) lastSeenOpen.clear();
				if (!queue.isEmpty()) queue.clear();
				if (!postCloseWatchlist.isEmpty()) postCloseWatchlist.clear();
				return;
			}

			long now = System.currentTimeMillis();

			// Pre-scan: walk post-close watchlist. Catches doors that left the scan box
			// between close and re-open — the PIN got wiped by retainAll, so the regular
			// scan would see SCAN_FIRST_SEEN with isOpen=true and miss the transition.
			Iterator<Map.Entry<BlockPos, Long>> wlit = postCloseWatchlist.entrySet().iterator();
			while (wlit.hasNext()) {
				Map.Entry<BlockPos, Long> we = wlit.next();
				BlockPos wpos = we.getKey();
				long age = now - we.getValue();
				if (age > WATCHLIST_TTL_MS) {
					wlit.remove();
					recordEvent("WATCHLIST_DROP", wpos, player, "reason=ttl age_ms=" + age);
					continue;
				}
				BlockState ws = level.getBlockState(wpos);
				if (!isTrackable(ws)) {
					wlit.remove();
					recordEvent("WATCHLIST_DROP", wpos, player, "reason=broken");
					continue;
				}
				boolean wOpen = ws.getValue(BlockStateProperties.OPEN);
				if (wOpen && !queue.containsKey(wpos)) {
					Entry entry = buildEntry(wpos, ws, player);
					if (entry != null) {
						queue.put(wpos, entry);
						recordEvent("WATCHLIST_REACQUIRE", wpos, player,
							"sideAtOpen=" + entry.sideAtOpen + " axis=" + entry.crossAxis);
						wlit.remove();
					}
				}
			}

			BlockPos playerPos = player.blockPosition();
			Map<BlockPos, Boolean> currentScan = new HashMap<>();
			BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

			for (int dy = SCAN_Y_MIN; dy <= SCAN_Y_MAX; dy++) {
				for (int dx = -SCAN_R; dx <= SCAN_R; dx++) {
					for (int dz = -SCAN_R; dz <= SCAN_R; dz++) {
						cursor.set(playerPos.getX() + dx, playerPos.getY() + dy, playerPos.getZ() + dz);
						BlockState state = level.getBlockState(cursor);
						if (!isTrackable(state)) continue;

						// Door uses two cells (lower+upper half). Normalize to lower half.
						BlockPos canonical = canonicalize(cursor.immutable(), state);
						if (currentScan.containsKey(canonical)) continue;

						boolean isOpen = state.getValue(BlockStateProperties.OPEN);
						currentScan.put(canonical, isOpen);

						Boolean prev = lastSeenOpen.get(canonical);
						if (prev == null) {
							recordEvent("SCAN_FIRST_SEEN", canonical, player, "isOpen=" + isOpen);
						} else if (prev != isOpen) {
							recordEvent("SCAN_TRANSITION", canonical, player,
								"prev=" + prev + " now=" + isOpen);
						}
						if (prev != null && !prev && isOpen && !queue.containsKey(canonical)) {
							Entry entry = buildEntry(canonical, state, player);
							if (entry != null) {
								queue.put(canonical, entry);
								recordEvent("QUEUE_ADD", canonical, player,
									"sideAtOpen=" + entry.sideAtOpen + " axis=" + entry.crossAxis);
							}
						}
					}
				}
			}

			// Detect evictions before retainAll wipes them.
			Set<BlockPos> evicted = new HashSet<>(lastSeenOpen.keySet());
			evicted.removeAll(currentScan.keySet());
			for (BlockPos p : evicted) {
				recordEvent("SCAN_EVICT", p, player, "was_open=" + lastSeenOpen.get(p));
			}
			lastSeenOpen.keySet().retainAll(currentScan.keySet());
			lastSeenOpen.putAll(currentScan);

			// Walk queue: close on cross, drop stale entries.
			Iterator<Map.Entry<BlockPos, Entry>> it = queue.entrySet().iterator();
			while (it.hasNext()) {
				Entry e = it.next().getValue();
				BlockState s = level.getBlockState(e.pos);
				if (!isTrackable(s)) {
					it.remove();
					recordEvent("QUEUE_DROP", e.pos, player, "reason=broken");
					continue;
				}
				if (!s.getValue(BlockStateProperties.OPEN)) {
					it.remove();
					recordEvent("QUEUE_DROP", e.pos, player, "reason=closed_externally");
					continue;
				}
				if (now - e.openedAtMs > TTL_MS) {
					it.remove();
					recordEvent("QUEUE_DROP", e.pos, player,
						"reason=ttl age_ms=" + (now - e.openedAtMs));
					continue;
				}

				int currentSide = sideOf(player, e.crossAxis, e.crossCoord);
				double offset = playerCoord(player, e.crossAxis) - e.crossCoord;
				if (currentSide != e.sideAtOpen && Math.abs(offset) > CROSS_BUFFER) {
					InteractionResult ir = tryClose(mc, e.pos);
					recordEvent("CLOSE_FIRE", e.pos, player,
						"result=" + ir + " offset=" + String.format("%.2f", offset)
							+ " sideAtOpen=" + e.sideAtOpen + " currentSide=" + currentSide);
					it.remove();
					// Pin so same-tick-window re-open registers as a fresh transition.
					lastSeenOpen.put(e.pos, false);
					recordEvent("PIN", e.pos, player, "lastSeenOpen=false");
					// Watchlist persists beyond scan box — re-acquires if door is
					// re-opened while out of range and the pin gets wiped by retainAll.
					postCloseWatchlist.put(e.pos, now);
					recordEvent("WATCHLIST_ADD", e.pos, player, "ttl_ms=" + WATCHLIST_TTL_MS);
				}
			}
		}
	}

	private static InteractionResult tryClose(Minecraft mc, BlockPos pos) {
		MultiPlayerGameMode gameMode = mc.gameMode;
		LocalPlayer player = mc.player;
		if (gameMode == null || player == null) return InteractionResult.FAIL;
		Vec3 hitLoc = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
		BlockHitResult hit = new BlockHitResult(hitLoc, Direction.UP, pos, false);
		InteractionResult ir = gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
		if (ir.consumesAction()) {
			player.swing(InteractionHand.MAIN_HAND);
		}
		return ir;
	}

	private static boolean isTrackable(BlockState state) {
		Block block = state.getBlock();
		if (block == Blocks.IRON_DOOR) return false;
		return block instanceof DoorBlock || block instanceof FenceGateBlock;
	}

	private static BlockPos canonicalize(BlockPos pos, BlockState state) {
		if (state.getBlock() instanceof DoorBlock) {
			DoubleBlockHalf half = state.getValue(DoorBlock.HALF);
			return half == DoubleBlockHalf.UPPER ? pos.below() : pos;
		}
		return pos;
	}

	private static Entry buildEntry(BlockPos pos, BlockState state, LocalPlayer player) {
		Direction facing;
		if (state.getBlock() instanceof DoorBlock) {
			facing = state.getValue(DoorBlock.FACING);
		} else if (state.getBlock() instanceof FenceGateBlock) {
			facing = state.getValue(FenceGateBlock.FACING);
		} else {
			return null;
		}
		Direction.Axis crossAxis = facing.getAxis();
		double crossCoord = (crossAxis == Direction.Axis.X) ? pos.getX() + 0.5 : pos.getZ() + 0.5;
		int sideAtOpen = sideOf(player, crossAxis, crossCoord);
		return new Entry(pos, crossAxis, crossCoord, sideAtOpen, System.currentTimeMillis());
	}

	private static double playerCoord(LocalPlayer player, Direction.Axis axis) {
		return axis == Direction.Axis.X ? player.getX() : player.getZ();
	}

	private static int sideOf(LocalPlayer player, Direction.Axis axis, double doorCoord) {
		return playerCoord(player, axis) > doorCoord ? 1 : -1;
	}

	private static void recordEvent(String type, BlockPos doorPos, LocalPlayer player, String detail) {
		Event ev = new Event(tickCounter, System.currentTimeMillis(), type, doorPos,
			player.getX(), player.getY(), player.getZ(), detail);
		if (events.size() >= EVENT_BUFFER_SIZE) events.removeFirst();
		events.addLast(ev);
		HomunculusClient.LOGGER.info(
			"[DoorCourtesy] t={} {} door=({},{},{}) player=({},{},{}) {}",
			tickCounter, type, doorPos.getX(), doorPos.getY(), doorPos.getZ(),
			String.format("%.2f", player.getX()),
			String.format("%.2f", player.getY()),
			String.format("%.2f", player.getZ()),
			detail);
	}

	/** Snapshot for the debug endpoint. Safe to call from any thread. */
	public static Map<String, Object> snapshot() {
		synchronized (LOCK) {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("tick", tickCounter);

			List<Map<String, Object>> queueOut = new ArrayList<>();
			long now = System.currentTimeMillis();
			for (Entry e : queue.values()) {
				Map<String, Object> q = new LinkedHashMap<>();
				q.put("pos", List.of(e.pos.getX(), e.pos.getY(), e.pos.getZ()));
				q.put("crossAxis", e.crossAxis.toString());
				q.put("crossCoord", e.crossCoord);
				q.put("sideAtOpen", e.sideAtOpen);
				q.put("age_ms", now - e.openedAtMs);
				queueOut.add(q);
			}
			out.put("queue", queueOut);

			List<Map<String, Object>> seen = new ArrayList<>();
			for (Map.Entry<BlockPos, Boolean> me : lastSeenOpen.entrySet()) {
				BlockPos p = me.getKey();
				Map<String, Object> s = new LinkedHashMap<>();
				s.put("pos", List.of(p.getX(), p.getY(), p.getZ()));
				s.put("open", me.getValue());
				seen.add(s);
			}
			out.put("lastSeenOpen", seen);

			List<Map<String, Object>> wlOut = new ArrayList<>();
			for (Map.Entry<BlockPos, Long> we : postCloseWatchlist.entrySet()) {
				BlockPos p = we.getKey();
				Map<String, Object> w = new LinkedHashMap<>();
				w.put("pos", List.of(p.getX(), p.getY(), p.getZ()));
				w.put("age_ms", now - we.getValue());
				wlOut.add(w);
			}
			out.put("postCloseWatchlist", wlOut);

			List<Map<String, Object>> evOut = new ArrayList<>();
			for (Event e : events) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("tick", e.tick);
				m.put("ts_ago_ms", now - e.ts);
				m.put("type", e.type);
				m.put("door", List.of(e.doorPos.getX(), e.doorPos.getY(), e.doorPos.getZ()));
				m.put("player", List.of(round2(e.playerX), round2(e.playerY), round2(e.playerZ)));
				m.put("detail", e.detail);
				evOut.add(m);
			}
			out.put("events", evOut);

			return out;
		}
	}

	private static double round2(double v) {
		return Math.round(v * 100.0) / 100.0;
	}
}
