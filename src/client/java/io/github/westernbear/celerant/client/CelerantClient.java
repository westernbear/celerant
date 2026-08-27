package io.github.westernbear.celerant.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.westernbear.celerant.client.net.CelerantClientNet;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

public class CelerantClient implements ClientModInitializer {
	private static final KeyMapping.Category INTERFACE_CATEGORY =
		KeyMapping.Category.register(Identifier.fromNamespaceAndPath("celerant", "interface"));
	private static final KeyMapping OPEN_UI_KEY = new KeyMapping("key.celerant.open_ui",
		InputConstants.Type.KEYSYM, InputConstants.KEY_V, INTERFACE_CATEGORY);
	private static final KeyMapping OPEN_RADIAL_KEY = new KeyMapping("key.celerant.open_radial",
		InputConstants.Type.KEYSYM, InputConstants.KEY_B, INTERFACE_CATEGORY);
	private volatile boolean disconnectPending;

	@Override
	public void onInitializeClient() {
		KeyMappingHelper.registerKeyMapping(OPEN_UI_KEY);
		KeyMappingHelper.registerKeyMapping(OPEN_RADIAL_KEY);
		VrmRuntime.initialize();
		CelerantConfig.INSTANCE.initialize();
		CelerantClientNet.init();
		VrmClientCommands.register();
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(CelerantClientNet::announce));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			disconnectPending = true;
			CelerantClientNet.onDisconnect();
		});
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			CelerantConfig.INSTANCE.syncRuntimeState();
			while (OPEN_UI_KEY.consumeClick()) {
				CelerantConfig.INSTANCE.open();
			}
			while (OPEN_RADIAL_KEY.consumeClick()) {
				RadialMenuActions.open();
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

	static KeyMapping radialKey() {
		return OPEN_RADIAL_KEY;
	}
}
