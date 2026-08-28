package io.github.westernbear.celerant.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.westernbear.celerant.client.VrmRuntime;
import io.github.westernbear.celerant.client.net.CelerantNetworking;
import io.github.westernbear.celerant.loco.LocoParams;

public final class CelerantApiImpl extends CelerantApi {

	private final List<AvatarLifecycleListener> listeners = new CopyOnWriteArrayList<>();

	public CelerantApiImpl() {
		register(this);
	}

	@Override
	public Optional<VrmAvatarHandle> localAvatar() {
		VrmRuntime runtime = VrmRuntime.getInstance();
		if (runtime.loadedPath() == null) {
			return Optional.empty();
		}
		return Optional.of(new VrmAvatarHandleImpl(runtime));
	}

	@Override
	public LocoParams remoteLoco(UUID playerId) {
		return CelerantNetworking.remoteLoco(playerId);
	}

	@Override
	public boolean isPaperPluginPresent() {
		return CelerantNetworking.isPluginPresent();
	}

	@Override
	public void registerAvatarListener(AvatarLifecycleListener listener) {
		listeners.add(listener);
	}

	public void fire(AvatarLifecycleListener.Event event) {
		Optional<java.nio.file.Path> path = Optional.ofNullable(VrmRuntime.getInstance().loadedPath());
		for (AvatarLifecycleListener listener : listeners) {
			listener.onAvatarEvent(event, path);
		}
	}

	private record VrmAvatarHandleImpl(VrmRuntime runtime) implements VrmAvatarHandle {

		@Override
		public Optional<java.nio.file.Path> loadedPath() {
			return Optional.ofNullable(runtime.loadedPath());
		}

		@Override
		public float scale() {
			return runtime.scale();
		}

		@Override
		public boolean avatarEnabled() {
			return runtime.isAvatarEnabled();
		}

		@Override
		public Optional<String> activeExpression() {
			return Optional.ofNullable(runtime.activeExpression());
		}

		@Override
		public float activeExpressionWeight() {
			return runtime.activeExpressionWeight();
		}
	}
}
