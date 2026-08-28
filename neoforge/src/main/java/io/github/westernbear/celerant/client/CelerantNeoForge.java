package io.github.westernbear.celerant.client;

import com.mojang.blaze3d.platform.InputConstants;

import io.github.westernbear.celerant.api.CelerantApiImpl;
import io.github.westernbear.celerant.client.net.CelerantNetworking;
import io.github.westernbear.celerant.platform.NeoForgePlatformHelper;
import io.github.westernbear.celerant.platform.Services;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(CelerantNeoForge.MOD_ID)
public final class CelerantNeoForge {
	public static final String MOD_ID = "celerant";
	private volatile boolean disconnectPending;

	public CelerantNeoForge(IEventBus modEventBus) {
		if (!FMLEnvironment.getDist().isClient()) {
			return;
		}
		NeoForgePlatformHelper.bindModBus(modEventBus);
		NeoForgePlatformHelper.bindGameBus(NeoForge.EVENT_BUS);
		NeoForge.EVENT_BUS.addListener(this::onJoin);
		NeoForge.EVENT_BUS.addListener(this::onDisconnect);
		new CelerantApiImpl();
		Services.PLATFORM.registerKeyMapping(OPEN_UI_KEY);
		Services.PLATFORM.registerKeyMapping(OPEN_RADIAL_KEY);
		VrmRuntime.initialize();
		CelerantNeoForgeConfig.INSTANCE.initialize();
		Services.PLATFORM.registerClientNetworking();
		Services.PLATFORM.registerClientCommands();
		Services.PLATFORM.registerJoin(CelerantNetworking::announce);
		Services.PLATFORM.registerDisconnect(() -> {
			disconnectPending = true;
			CelerantNetworking.onDisconnect();
		});
		Services.PLATFORM.registerClientTick(() -> {
			CelerantNeoForgeConfig.INSTANCE.syncRuntimeState();
			while (OPEN_UI_KEY.consumeClick()) {
				CelerantNeoForgeConfig.INSTANCE.open();
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

	private static final KeyMapping.Category INTERFACE_CATEGORY =
		KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "interface"));
	private static final KeyMapping OPEN_UI_KEY = new KeyMapping("key.celerant.open_ui",
		InputConstants.Type.KEYSYM, InputConstants.KEY_V, INTERFACE_CATEGORY);
	private static final KeyMapping OPEN_RADIAL_KEY = new KeyMapping("key.celerant.open_radial",
		InputConstants.Type.KEYSYM, InputConstants.KEY_B, INTERFACE_CATEGORY);

	private void onJoin(ClientPlayerNetworkEvent.LoggingIn event) {
		Minecraft.getInstance().execute(NeoForgePlatformHelper::runJoinCallback);
	}

	private void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
		disconnectPending = true;
		NeoForgePlatformHelper.runDisconnectCallback();
	}

	static KeyMapping uiKey() {
		return OPEN_UI_KEY;
	}

	static KeyMapping radialKey() {
		return OPEN_RADIAL_KEY;
	}
}
