package io.github.westernbear.celerant.gametest;

import io.github.westernbear.celerant.client.toon.ToonShader;
import io.github.westernbear.celerant.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** NeoForge client harness for smoke and shader-matrix entry points. */
public final class CelerantNeoForgeGameTestRunner {

	private enum Phase {
		WAIT_WORLD,
		RUN_SMOKE,
		DONE
	}

	private static Phase phase = Phase.WAIT_WORLD;
	private static int ticks;
	private static boolean finished;
	private static boolean failed;
	private static boolean worldRequested;

	private CelerantNeoForgeGameTestRunner() {
	}

	public static boolean finished() {
		return finished || failed;
	}

	public static void fail(Throwable throwable) {
		failed = true;
		throw new AssertionError("NeoForge client GameTest failed", throwable);
	}

	public static void tick() {
		if (finished || failed) {
			return;
		}
		ticks++;
		Minecraft client = Minecraft.getInstance();
		switch (phase) {
			case WAIT_WORLD -> {
				if (client.level != null && client.player != null) {
					phase = Phase.RUN_SMOKE;
				} else if (!worldRequested && client.gui.screen() instanceof TitleScreen) {
					// NeoForge has no client GameTest harness; open a fresh flat test world ourselves.
					worldRequested = true;
					client.createWorldOpenFlows().createFreshLevel("celerant-gametest-" + System.currentTimeMillis(),
						new LevelSettings("Celerant GameTest", GameType.CREATIVE, LevelSettings.DifficultySettings.DEFAULT,
							true, WorldDataConfiguration.DEFAULT),
						WorldOptions.testWorldWithRandomSeed(), WorldPresets::createTestWorldDimensions, client.gui.screen());
				} else if (ticks > 2400) {
					fail(new IllegalStateException("client never entered a world"));
				}
			}
			case RUN_SMOKE -> {
				runSmoke();
				phase = Phase.DONE;
				finished = true;
				client.execute(client::stop);
			}
			case DONE -> {
			}
		}
	}

	private static void runSmoke() {
		require(Services.PLATFORM.getPlatformName().equals("NeoForge"), "platform must be NeoForge");
		ToonShader.setEnabled(true);
		require(ToonShader.isEnabled(), "toon shading must be enabled");
		ToonShader.setEnabled(false);
		require(!ToonShader.isEnabled(), "toon shading must disable");
		System.out.println("[Celerant NeoForge GameTest] smoke passed (platform="
			+ Services.PLATFORM.getPlatformName() + ", iris=" + Services.PLATFORM.isShaderPackInUse() + ")");
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
