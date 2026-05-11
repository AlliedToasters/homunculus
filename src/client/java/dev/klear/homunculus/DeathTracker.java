package dev.klear.homunculus;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side death tracking. Detects the alive→dead edge each tick on the client player,
 * snapshots death position + cause from {@link LocalPlayer#getLastDamageSource()}, and finalizes
 * with respawn position once the player is alive again. See SPEC.md "GET /deaths".
 *
 * Thread model: write side runs on the client thread (tick handler). Read side ({@link #snapshot})
 * runs on HTTP worker threads. Synchronized on the singleton.
 */
public final class DeathTracker {
	private static final int RING_CAPACITY = 10;

	public static final DeathTracker INSTANCE = new DeathTracker();

	private final Deque<Record> ring = new ArrayDeque<>(RING_CAPACITY);
	private Record pending;
	private boolean wasAlive;
	private LocalPlayer trackedPlayer;

	private DeathTracker() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(INSTANCE::onTick);
	}

	private void onTick(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null) {
			// Disconnect / world unload — reset edge state, drop any unfinalized pending.
			synchronized (this) {
				wasAlive = false;
				trackedPlayer = null;
				pending = null;
			}
			return;
		}

		// New player instance (respawn-to-new-entity, dimension change, reconnect).
		// A respawn typically arrives as a fresh LocalPlayer with isAlive() == true, so the
		// alive→dead edge below never sees the false→true transition. If we have a pending
		// record and the new player is alive, this swap IS the respawn — finalize here.
		if (player != trackedPlayer) {
			synchronized (this) {
				trackedPlayer = player;
				wasAlive = player.isAlive();
				if (pending != null && wasAlive) {
					BlockPos pos = player.blockPosition();
					pending.respawnX = pos.getX();
					pending.respawnY = pos.getY();
					pending.respawnZ = pos.getZ();
					pending.respawnSet = true;
					pushLocked(pending);
					pending = null;
				}
			}
			return;
		}

		boolean alive = player.isAlive();

		if (wasAlive && !alive) {
			// Death edge.
			Record r = buildPendingRecord(player);
			synchronized (this) {
				pending = r;
			}
		} else if (!wasAlive && alive && pending != null) {
			// Respawn edge.
			synchronized (this) {
				if (pending != null) {
					BlockPos pos = player.blockPosition();
					pending.respawnX = pos.getX();
					pending.respawnY = pos.getY();
					pending.respawnZ = pos.getZ();
					pending.respawnSet = true;
					pushLocked(pending);
					pending = null;
				}
			}
		}
		wasAlive = alive;
	}

	private static Record buildPendingRecord(LocalPlayer player) {
		Record r = new Record();
		r.timestamp = System.currentTimeMillis();
		BlockPos pos = player.blockPosition();
		r.deathX = pos.getX();
		r.deathY = pos.getY();
		r.deathZ = pos.getZ();

		DamageSource src = player.getLastDamageSource();
		if (src != null) {
			r.message = src.getLocalizedDeathMessage(player).getString();
			r.cause = bucketCause(src);
		} else {
			// Combat tracker fallback (combat tracker always builds *some* message).
			Component fallback = player.getCombatTracker().getDeathMessage();
			r.message = fallback != null ? fallback.getString() : "death";
			r.cause = "other";
		}
		return r;
	}

	private static String bucketCause(DamageSource src) {
		String msgId = src.getMsgId();
		Entity attacker = src.getEntity();
		if (attacker instanceof Player p) {
			return "player:" + p.getGameProfile().getName();
		}
		if (attacker != null) {
			String entityId = BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()).toString();
			return "mob:" + entityId;
		}
		return switch (msgId) {
			case "fall" -> "fall";
			case "lava" -> "lava";
			case "inFire", "onFire", "hotFloor" -> "fire";
			case "drown" -> "drown";
			case "outOfWorld" -> "void";
			case "starve" -> "starvation";
			case "cactus" -> "cactus";
			case "freeze" -> "freeze";
			case "inWall", "cramming" -> "suffocation";
			case "explosion", "explosion.player" -> "explosion";
			default -> "other";
		};
	}

	private void pushLocked(Record r) {
		if (ring.size() == RING_CAPACITY) {
			ring.removeFirst();
		}
		ring.addLast(r);
	}

	/**
	 * Snapshot of records with {@code timestamp > since}, oldest → newest.
	 * Includes the unfinalized pending record (respawn_pos: null) if present.
	 * Safe to call from any thread.
	 */
	public synchronized List<Map<String, Object>> snapshot(long since) {
		List<Map<String, Object>> out = new ArrayList<>(ring.size() + 1);
		for (Record r : ring) {
			if (r.timestamp > since) out.add(r.toJson());
		}
		if (pending != null && pending.timestamp > since) {
			out.add(pending.toJson());
		}
		return out;
	}

	private static final class Record {
		long timestamp;
		String message;
		String cause;
		int deathX, deathY, deathZ;
		int respawnX, respawnY, respawnZ;
		boolean respawnSet;

		Map<String, Object> toJson() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("timestamp", timestamp);
			m.put("message", message);
			m.put("cause", cause);
			m.put("death_pos", List.of(deathX, deathY, deathZ));
			m.put("respawn_pos", respawnSet ? List.of(respawnX, respawnY, respawnZ) : null);
			return m;
		}
	}
}
