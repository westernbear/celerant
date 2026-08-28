package io.github.westernbear.celerant.client.net;

import net.minecraft.resources.Identifier;

public final class CelerantChannels {
	public static final Identifier AVATAR_META = Identifier.fromNamespaceAndPath("celerant", "avatar_meta");
	public static final Identifier AVATAR_CHUNK = Identifier.fromNamespaceAndPath("celerant", "avatar_chunk");
	public static final Identifier AVATAR_KEY = Identifier.fromNamespaceAndPath("celerant", "avatar_key");
	public static final Identifier LOCO = Identifier.fromNamespaceAndPath("celerant", "loco");
	public static final Identifier HELLO = Identifier.fromNamespaceAndPath("celerant", "hello");

	private CelerantChannels() {
	}
}
