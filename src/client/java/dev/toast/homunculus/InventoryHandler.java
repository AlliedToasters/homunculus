package dev.toast.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

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

public final class InventoryHandler implements HttpHandler {
	private static final long SNAPSHOT_TIMEOUT_MS = 2000;

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		try {
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				respond(exchange, 405, Map.of("error", "method not allowed"));
				return;
			}

			Map<String, Object> body;
			try {
				body = ClientThread.supply(InventoryHandler::snapshot)
						.get(SNAPSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				respond(exchange, 504, Map.of("error", "client thread timeout"));
				return;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				respond(exchange, 500, Map.of("error", "interrupted"));
				return;
			} catch (ExecutionException e) {
				HomunculusClient.LOGGER.error("inventory snapshot failed", e.getCause());
				respond(exchange, 500, Map.of("error", "internal error"));
				return;
			}

			if (body == null) {
				respond(exchange, 503, Map.of("error", "no player (not in world)"));
				return;
			}
			respond(exchange, 200, body);
		} finally {
			exchange.close();
		}
	}

	/** Runs on the client/render thread. Returns null if the player is not in a world. */
	private static Map<String, Object> snapshot() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) return null;
		Inventory inv = player.getInventory();

		List<Object> main = new ArrayList<>();
		NonNullList<ItemStack> items = inv.items;
		for (int i = 0; i < items.size(); i++) {
			ItemStack stack = items.get(i);
			if (!stack.isEmpty()) {
				main.add(slotEntry(i, stack));
			}
		}

		// armor list ordering: [0]=feet, [1]=legs, [2]=chest, [3]=head
		Map<String, Object> armor = new LinkedHashMap<>();
		armor.put("feet", stackOrNull(inv.armor.get(0)));
		armor.put("legs", stackOrNull(inv.armor.get(1)));
		armor.put("chest", stackOrNull(inv.armor.get(2)));
		armor.put("head", stackOrNull(inv.armor.get(3)));

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("main", main);
		result.put("armor", armor);
		result.put("offhand", stackOrNull(inv.offhand.get(0)));
		result.put("selected_slot", inv.selected);
		return result;
	}

	private static Map<String, Object> slotEntry(int slot, ItemStack stack) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("slot", slot);
		m.put("id", itemId(stack));
		m.put("count", stack.getCount());
		return m;
	}

	private static Object stackOrNull(ItemStack stack) {
		if (stack.isEmpty()) return null;
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", itemId(stack));
		m.put("count", stack.getCount());
		return m;
	}

	private static String itemId(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
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
