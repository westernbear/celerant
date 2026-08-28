package io.github.westernbear.celerant.api;

import java.nio.file.Path;
import java.util.Optional;

/** Read-only view of the locally loaded VRM avatar. */
public interface VrmAvatarHandle {

	Optional<Path> loadedPath();

	float scale();

	boolean avatarEnabled();

	Optional<String> activeExpression();

	float activeExpressionWeight();
}
