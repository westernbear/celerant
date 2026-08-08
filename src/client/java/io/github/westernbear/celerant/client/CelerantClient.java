package io.github.westernbear.celerant.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public class CelerantClient implements ClientModInitializer {
	private static final KeyMapping OPEN_UI_KEY = new KeyMapping("key.celerant.open_ui",
		InputConstants.Type.KEYSYM, InputConstants.KEY_V,
		KeyMapping.Category.register(Identifier.fromNamespaceAndPath("celerant", "interface")));
	private volatile boolean disconnectPending;

	@Override
	public void onInitializeClient() {
		KeyMappingHelper.registerKeyMapping(OPEN_UI_KEY);
		VrmRuntime.initialize();
		CelerantConfig.INSTANCE.initialize();
		VrmClientCommands.register();
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> disconnectPending = true);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			CelerantConfig.INSTANCE.syncRuntimeState();
			while (OPEN_UI_KEY.consumeClick()) {
				CelerantConfig.INSTANCE.open();
			}
			if (!disconnectPending) {
				return;
			}
			disconnectPending = false;
			VrmRuntime runtime = VrmRuntime.getInstance();
			runtime.unload();
			runtime.place(null);
		});
	}

	static KeyMapping uiKey() {
		return OPEN_UI_KEY;
	}
}
