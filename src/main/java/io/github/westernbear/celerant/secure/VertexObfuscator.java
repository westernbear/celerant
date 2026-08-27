package io.github.westernbear.celerant.secure;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Deterministic vertex scramble/restore using a secret key (AvaCrypt-style deterrence).
 */
public final class VertexObfuscator {
	private VertexObfuscator() {
	}

	public static byte[] deriveSubkey(byte[] masterKey, String purpose) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(masterKey);
			digest.update(purpose.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return digest.digest();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** In-place scramble of float XYZ interleaved positions. */
	public static void obfuscate(float[] positions, byte[] key) {
		transform(positions, key, false);
	}

	public static void restore(float[] positions, byte[] key) {
		transform(positions, key, true);
	}

	private static void transform(float[] positions, byte[] key, boolean inverse) {
		if (positions == null || key == null || key.length < 16) {
			throw new IllegalArgumentException("positions/key required");
		}
		byte[] sub = deriveSubkey(key, "vertex");
		ByteBuffer seedBuf = ByteBuffer.wrap(sub).order(ByteOrder.LITTLE_ENDIAN);
		long seed = seedBuf.getLong() ^ seedBuf.getLong();
		for (int i = 0; i + 2 < positions.length; i += 3) {
			long h = mix(seed, i);
			float ax = ((h & 0xFFFF) / 65535.0F) * 2.0F - 1.0F;
			float ay = (((h >>> 16) & 0xFFFF) / 65535.0F) * 2.0F - 1.0F;
			float az = (((h >>> 32) & 0xFFFF) / 65535.0F) * 2.0F - 1.0F;
			if (inverse) {
				positions[i] -= ax;
				positions[i + 1] -= ay;
				positions[i + 2] -= az;
			} else {
				positions[i] += ax;
				positions[i + 1] += ay;
				positions[i + 2] += az;
			}
		}
	}

	private static long mix(long seed, int index) {
		long x = seed ^ (index * 0x9E3779B97F4A7C15L);
		x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
		x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
		return x ^ (x >>> 31);
	}

	public static byte[] randomKey() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return key;
	}

	public static boolean keysEqual(byte[] a, byte[] b) {
		return Arrays.equals(a, b);
	}
}
