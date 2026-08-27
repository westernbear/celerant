package io.github.westernbear.celerant.client;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.westernbear.celerant.client.net.CelerantClientNet;
import io.github.westernbear.celerant.client.remote.RemoteAvatarManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * VRChat-like radial action cycle (same package as config/runtime for access).
 */
final class RadialMenuActions {
	private record Slice(Component label, Runnable action) {
	}

	private static final AtomicInteger INDEX = new AtomicInteger();

	private RadialMenuActions() {
	}

	static void open() {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}
		List<Slice> slices = slices();
		int i = Math.floorMod(INDEX.getAndIncrement(), slices.size());
		Slice slice = slices.get(i);
		client.player.sendSystemMessage(
			Component.translatable("celerant.radial.title").append(": ").append(slice.label()));
		slice.action().run();
	}

	/** GameTest: start radial cycle from the first slice. */
	static void resetIndexForTest() {
		INDEX.set(0);
	}

	private static List<Slice> slices() {
		VrmRuntime runtime = VrmRuntime.getInstance();
		return List.of(
			new Slice(Component.translatable("celerant.radial.expressions"), () -> {
				var names = runtime.expressionNames();
				if (!names.isEmpty()) {
					runtime.setExpression(names.get(0), 1.0F);
				}
			}),
			new Slice(Component.translatable("celerant.radial.clear_expression"), runtime::clearExpression),
			new Slice(Component.translatable("celerant.radial.toggle_avatar"), () -> {
				boolean next = !runtime.isLocalAvatarActive();
				runtime.setAvatarEnabled(next);
			}),
			new Slice(Component.translatable("celerant.radial.upload"), () -> {
				if (!CelerantClientNet.isPluginPresent()) {
					return;
				}
				String path = CelerantConfig.INSTANCE.modelPathValue();
				if (path != null && !path.isBlank()) {
					RemoteAvatarManager.uploadLocal(java.nio.file.Path.of(path.trim()));
				}
			}),
			new Slice(Component.translatable("celerant.radial.open_config"), CelerantConfig.INSTANCE::open)
		);
	}
}
