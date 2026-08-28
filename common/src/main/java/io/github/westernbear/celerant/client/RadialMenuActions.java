package io.github.westernbear.celerant.client;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.westernbear.celerant.client.net.CelerantNetworking;
import io.github.westernbear.celerant.client.remote.RemoteAvatarManager;
import io.github.westernbear.celerant.client.ui.RadialMenuScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Slice definitions and open/toggle entry for the VRChat-like radial HUD.
 */
public final class RadialMenuActions {
	public record Slice(Component label, Runnable action) {
		public void run() {
			action.run();
		}
	}

	private static final AtomicInteger EXPRESSION_INDEX = new AtomicInteger();

	private RadialMenuActions() {
	}

	static void open() {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}
		if (client.gui.screen() instanceof RadialMenuScreen) {
			client.gui.setScreen(null);
			return;
		}
		if (client.gui.screen() != null) {
			return;
		}
		client.gui.setScreen(new RadialMenuScreen());
	}

	/** GameTest: reset expression cycle cursor. */
	static void resetIndexForTest() {
		EXPRESSION_INDEX.set(0);
	}

	public static List<Slice> slices() {
		VrmRuntime runtime = VrmRuntime.getInstance();
		return List.of(
			new Slice(Component.translatable("celerant.radial.expressions"), () -> {
				var names = runtime.expressionNames();
				if (names.isEmpty()) {
					return;
				}
				int i = Math.floorMod(EXPRESSION_INDEX.getAndIncrement(), names.size());
				runtime.setExpression(names.get(i), 1.0F);
			}),
			new Slice(Component.translatable("celerant.radial.clear_expression"), runtime::clearExpression),
			new Slice(Component.translatable("celerant.radial.toggle_avatar"), () -> {
				runtime.setAvatarEnabled(!runtime.isLocalAvatarActive());
			}),
			new Slice(Component.translatable("celerant.radial.upload"), () -> {
				if (!CelerantNetworking.isPluginPresent()) {
					return;
				}
				String path = io.github.westernbear.celerant.platform.Services.PLATFORM.modelPathValue();
				if (path != null && !path.isBlank()) {
					RemoteAvatarManager.uploadLocal(java.nio.file.Path.of(path.trim()));
				}
			}),
			new Slice(Component.translatable("celerant.radial.open_config"),
				io.github.westernbear.celerant.platform.Services.PLATFORM::openConfig),
			new Slice(Component.translatable("celerant.radial.close"), () -> {
			})
		);
	}
}
