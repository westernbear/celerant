package io.github.westernbear.celerant.gametest;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod("celerant_gametest")
public final class CelerantNeoForgeGameTestMod {

	public CelerantNeoForgeGameTestMod(IEventBus modEventBus) {
		if (!FMLEnvironment.getDist().isClient()) {
			return;
		}
		if (!Boolean.getBoolean("celerant.gametest")) {
			return;
		}
		NeoForge.EVENT_BUS.addListener(CelerantNeoForgeGameTestMod::onClientTick);
	}

	private static void onClientTick(ClientTickEvent.Post event) {
		if (CelerantNeoForgeGameTestRunner.finished()) {
			return;
		}
		try {
			CelerantNeoForgeGameTestRunner.tick();
		} catch (Throwable throwable) {
			CelerantNeoForgeGameTestRunner.fail(throwable);
		}
	}
}
