package io.github.westernbear.celerant.api;

import java.util.Optional;
import java.util.UUID;

import io.github.westernbear.celerant.loco.LocoParams;

/** Public entry point registered by loader-specific Celerant bootstrap. */
public abstract class CelerantApi {

	private static CelerantApi instance;

	public static CelerantApi get() {
		CelerantApi current = instance;
		if (current == null) {
			throw new IllegalStateException("Celerant has not been initialized yet");
		}
		return current;
	}

	protected static void register(CelerantApi api) {
		instance = java.util.Objects.requireNonNull(api, "api");
	}

	public abstract Optional<VrmAvatarHandle> localAvatar();

	public abstract LocoParams remoteLoco(UUID playerId);

	public abstract boolean isPaperPluginPresent();

	public abstract void registerAvatarListener(AvatarLifecycleListener listener);
}
