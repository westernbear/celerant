package io.github.westernbear.celerant.platform;

import java.util.ServiceLoader;

import io.github.westernbear.celerant.Celerant;
import io.github.westernbear.celerant.platform.services.ICelerantPlatformHelper;

public final class Services {

	public static final ICelerantPlatformHelper PLATFORM = load(ICelerantPlatformHelper.class);

	private Services() {
	}

	private static <T> T load(Class<T> clazz) {
		T loadedService = ServiceLoader.load(clazz, Services.class.getClassLoader())
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("No implementation for " + clazz.getName()));
		Celerant.LOGGER.debug("Loaded {} for service {}", loadedService, clazz);
		return loadedService;
	}
}
