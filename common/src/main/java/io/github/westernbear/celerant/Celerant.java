package io.github.westernbear.celerant;

import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Celerant {
	public static final String MOD_ID = "celerant";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private Celerant() {
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
