package io.github.westernbear.celerant.secure;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Keystream XOR used as a Hardened pre-AES binary scramble. */
public final class BinaryScrambler {
	private BinaryScrambler() {
	}

	public static byte[] scramble(byte[] data, byte[] key) {
		return transform(data, key);
	}

	public static byte[] restore(byte[] data, byte[] key) {
		return transform(data, key);
	}

	private static byte[] transform(byte[] data, byte[] key) {
		byte[] sub = VertexObfuscator.deriveSubkey(key, "binary");
		byte[] out = data.clone();
		ByteBuffer seed = ByteBuffer.wrap(sub).order(ByteOrder.LITTLE_ENDIAN);
		long state = seed.getLong() ^ seed.getLong();
		for (int i = 0; i < out.length; i++) {
			state = state * 0x5DEECE66DL + 0xBL;
			out[i] ^= (byte) ((state >>> 24) & 0xFF);
		}
		return out;
	}
}
