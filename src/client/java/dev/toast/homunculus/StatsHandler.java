package dev.toast.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodData;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class StatsHandler implements HttpHandler {
	private static final long SNAPSHOT_TIMEOUT_MS = 2000;

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		try {
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				respond(exchange, 405, failure("bad_request", "method not allowed"));
				return;
			}

			Map<String, Object> body;
			try {
				body = ClientThread.supply(StatsHandler::snapshot)
						.get(SNAPSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				respond(exchange, 504, failure("internal_error", "client thread timeout"));
				return;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				respond(exchange, 500, failure("internal_error", "interrupted"));
				return;
			} catch (ExecutionException e) {
				HomunculusClient.LOGGER.error("stats snapshot failed", e.getCause());
				respond(exchange, 500, failure("internal_error", "internal error"));
				return;
			}

			if (body == null) {
				respond(exchange, 503, failure("internal_error", "no player (not in world)"));
				return;
			}
			respond(exchange, 200, body);
		} finally {
			exchange.close();
		}
	}

	/** Runs on the client/render thread. Returns null if the player is not in a world. */
	private static Map<String, Object> snapshot() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null) return null;

		Map<String, Object> m = new LinkedHashMap<>();
		m.put("health", p.getHealth());
		m.put("max_health", p.getMaxHealth());

		FoodData food = p.getFoodData();
		m.put("food", food.getFoodLevel());
		m.put("saturation", food.getSaturationLevel());

		m.put("armor", p.getArmorValue());
		m.put("air", p.getAirSupply());
		m.put("max_air", p.getMaxAirSupply());

		Map<String, Object> xp = new LinkedHashMap<>();
		xp.put("level", p.experienceLevel);
		xp.put("progress", p.experienceProgress);
		xp.put("total", p.totalExperience);
		m.put("experience", xp);

		List<Map<String, Object>> effects = new ArrayList<>();
		for (MobEffectInstance eff : p.getActiveEffects()) {
			Map<String, Object> e = new LinkedHashMap<>();
			String id = eff.getEffect().unwrapKey()
					.map(k -> k.location().toString())
					.orElse("unknown");
			e.put("id", id);
			e.put("amplifier", eff.getAmplifier());
			e.put("duration_ticks", eff.getDuration());
			e.put("infinite", eff.isInfiniteDuration());
			e.put("ambient", eff.isAmbient());
			effects.add(e);
		}
		m.put("effects", effects);

		m.put("dimension", p.level().dimension().location().toString());

		// Day-time: total ticks since world start. % 24000 = position in
		// current day cycle (0=sunrise, 6000=noon, 12000=dusk, 18000=midnight).
		// // 24000 = day count. Used by the agent harness to surface
		// "minutes until nightfall" hints for survival prioritization.
		long worldTime = p.level().getDayTime();
		m.put("day_ticks", worldTime % 24000L);
		m.put("day_count", worldTime / 24000L);

		// Biome at the player's current block. Spawn biome is a strong
		// confound for survival rollouts (forest = easy wood, desert = none,
		// ocean = stranded) — log it per-turn so post-hoc analysis can bin by it.
		String biomeId = p.level().getBiome(p.blockPosition()).unwrapKey()
				.map(k -> k.location().toString())
				.orElse("unknown");
		m.put("biome", biomeId);

		MultiPlayerGameMode gm = mc.gameMode;
		m.put("gamemode", gm != null ? gm.getPlayerMode().getName() : null);

		m.put("on_ground", p.onGround());
		m.put("in_water", p.isInWater());
		m.put("in_lava", p.isInLava());
		m.put("on_fire", p.isOnFire());

		return m;
	}

	private static Map<String, Object> failure(String reason, String message) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("success", false);
		m.put("reason", reason);
		m.put("message", message);
		return m;
	}

	private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
		byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("content-type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}
}
