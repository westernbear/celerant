package io.github.westernbear.celerant.secure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Re-encrypted on-disk cache for downloaded remote avatars.
 */
public final class EncryptedAvatarCache {
	private final Path root;

	public EncryptedAvatarCache(Path root) {
		this.root = root;
	}

	public Path pathFor(String avatarId) {
		String safe = avatarId.replaceAll("[^a-zA-Z0-9._-]", "_");
		return root.resolve(safe + ".cenv");
	}

	public void write(String avatarId, byte[] envelopeBytes, byte[] cacheKey) throws IOException {
		Files.createDirectories(root);
		byte[] wrapped = AvatarEnvelope.wrap(envelopeBytes, cacheKey);
		Files.write(pathFor(avatarId), wrapped);
	}

	public byte[] read(String avatarId, byte[] cacheKey) throws IOException {
		Path path = pathFor(avatarId);
		if (!Files.isRegularFile(path)) {
			return null;
		}
		byte[] disk = Files.readAllBytes(path);
		return AvatarEnvelope.unwrap(disk, cacheKey);
	}

	public void delete(String avatarId) throws IOException {
		Files.deleteIfExists(pathFor(avatarId));
	}

	public static byte[] machineCacheKey(byte[] sessionKey) {
		return AvatarEnvelope.sha256(concat("celerant-cache".getBytes(), sessionKey));
	}

	private static byte[] concat(byte[] a, byte[] b) {
		byte[] out = Arrays.copyOf(a, a.length + b.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}
}
