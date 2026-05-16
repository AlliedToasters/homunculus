package dev.klear.homunculus;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public final class HomunculusClient implements ClientModInitializer {
	public static final String MOD_ID = "homunculus";
	public static final int HTTP_PORT = Integer.getInteger("homunculus.port", 25566);
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private final HomunculusHttpServer httpServer = new HomunculusHttpServer(HTTP_PORT);

	@Override
	public void onInitializeClient() {
		DeathTracker.register();
		FurnaceTicker.register();
		DoorCourtesy.register();
		Evasion.register();
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			try {
				httpServer.start();
			} catch (IOException e) {
				LOGGER.error("Failed to bind HTTP server on 127.0.0.1:{} — Homunculus disabled", HTTP_PORT, e);
			}
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> httpServer.stop());
	}
}
