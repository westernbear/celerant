package io.github.westernbear.celerant.secure;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-GCM envelope around an obfuscated avatar payload.
 */
public final class AvatarEnvelope {
	private static final int GCM_IV = 12;
	private static final int GCM_TAG = 128;

	private AvatarEnvelope() {
	}

	public static byte[] sha256(byte[] data) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(data);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	public static byte[] wrap(byte[] plaintext, byte[] key32) {
		try {
			byte[] iv = new byte[GCM_IV];
			new SecureRandom().nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			SecretKey key = new SecretKeySpec(Arrays.copyOf(key32, 32), "AES");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG, iv));
			byte[] cipherText = cipher.doFinal(plaintext);
			ByteBuffer out = ByteBuffer.allocate(1 + GCM_IV + cipherText.length);
			out.put((byte) 1);
			out.put(iv);
			out.put(cipherText);
			return out.array();
		} catch (Exception e) {
			throw new IllegalStateException("AES-GCM wrap failed", e);
		}
	}

	public static byte[] unwrap(byte[] envelope, byte[] key32) {
		try {
			if (envelope == null || envelope.length < 1 + GCM_IV + 16 || envelope[0] != 1) {
				throw new IllegalArgumentException("invalid envelope");
			}
			ByteBuffer buf = ByteBuffer.wrap(envelope);
			buf.get();
			byte[] iv = new byte[GCM_IV];
			buf.get(iv);
			byte[] cipherText = new byte[buf.remaining()];
			buf.get(cipherText);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			SecretKey key = new SecretKeySpec(Arrays.copyOf(key32, 32), "AES");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG, iv));
			return cipher.doFinal(cipherText);
		} catch (Exception e) {
			throw new IllegalStateException("AES-GCM unwrap failed", e);
		}
	}
}
