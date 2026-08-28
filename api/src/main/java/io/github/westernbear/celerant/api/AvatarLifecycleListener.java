package io.github.westernbear.celerant.api;

import java.nio.file.Path;
import java.util.Optional;

@FunctionalInterface
public interface AvatarLifecycleListener {

	enum Event {
		LOADED,
		UNLOADED,
		AVATAR_ENABLED,
		AVATAR_DISABLED
	}

	void onAvatarEvent(Event event, Optional<Path> path);
}
