package io.github.westernbear.celerant.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.CommandDispatcher;

import io.github.westernbear.celerant.api.CelerantApiImpl;
import io.github.westernbear.celerant.client.net.CelerantNetworking;
import io.github.westernbear.celerant.platform.Services;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public class CelerantFabricClient implements ClientModInitializer {
	private static final KeyMapping.Category INTERFACE_CATEGORY =
		KeyMapping.Category.register(Identifier.fromNamespaceAndPath("celerant", "interface"));
	private static final KeyMapping OPEN_UI_KEY = new KeyMapping("key.celerant.open_ui",
		InputConstants.Type.KEYSYM, InputConstants.KEY_V, INTERFACE_CATEGORY);
	private static final KeyMapping OPEN_RADIAL_KEY = new KeyMapping("key.celerant.open_radial",
		InputConstants.Type.KEYSYM, InputConstants.KEY_B, INTERFACE_CATEGORY);
	private volatile boolean disconnectPending;

	@Override
	public void onInitializeClient() {
		new CelerantApiImpl();
		Services.PLATFORM.registerKeyMapping(OPEN_UI_KEY);
		Services.PLATFORM.registerKeyMapping(OPEN_RADIAL_KEY);
		VrmRuntime.initialize();
		CelerantConfig.INSTANCE.initialize();
		Services.PLATFORM.registerClientNetworking();
		Services.PLATFORM.registerClientCommands();
		Services.PLATFORM.registerJoin(CelerantNetworking::announce);
		Services.PLATFORM.registerDisconnect(() -> {
			disconnectPending = true;
			CelerantNetworking.onDisconnect();
		});
		Services.PLATFORM.registerClientTick(() -> {
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
