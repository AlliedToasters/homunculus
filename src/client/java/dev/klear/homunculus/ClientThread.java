package dev.klear.homunculus;

import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Bridge between off-thread callers (e.g. the embedded HTTP server) and the game thread.
 * All reads/mutations of {@link Minecraft} state must go through this — touching
 * client/player state from the wrong thread is the primary source of intermittent crashes.
 */
public final class ClientThread {
	private ClientThread() {}

	public static <T> CompletableFuture<T> supply(Supplier<T> task) {
		return Minecraft.getInstance().submit(task);
	}
}
