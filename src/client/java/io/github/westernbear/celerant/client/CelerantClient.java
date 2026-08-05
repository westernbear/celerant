package io.github.westernbear.celerant.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class CelerantClient implements ClientModInitializer {
	private volatile boolean disconnectPending;

	@Override
	public void onInitializeClient() {
		VrmRuntime.initialize();
		VrmClientCommands.register();
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> disconnectPending = true);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!disconnectPending) {
				return;
			}
			disconnectPending = false;
			VrmRuntime runtime = VrmRuntime.getInstance();
			runtime.unload();
			runtime.place(null);
		});
	}
}
