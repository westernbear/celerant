package io.github.westernbear.celerant.secure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class SecureRoundTripTest {
	@Test
	void vertexObfuscatorRoundTrip() {
		float[] pos = {1, 2, 3, 4, 5, 6};
		float[] copy = pos.clone();
		byte[] key = VertexObfuscator.randomKey();
		VertexObfuscator.obfuscate(pos, key);
		assertFalse(Arrays.equals(copy, pos));
		VertexObfuscator.restore(pos, key);
		assertArrayEquals(copy, pos, 1e-5F);
	}

	@Test
	void aesEnvelopeRoundTrip() {
		byte[] plain = "hello-vrm-payload".getBytes();
		byte[] key = VertexObfuscator.randomKey();
		byte[] env = AvatarEnvelope.wrap(plain, key);
		assertArrayEquals(plain, AvatarEnvelope.unwrap(env, key));
	}

	@Test
	void binaryScramblerRoundTrip() {
		byte[] plain = new byte[] {1, 2, 3, 4, 5, 9, 8, 7};
		byte[] key = VertexObfuscator.randomKey();
		byte[] scrambled = BinaryScrambler.scramble(plain, key);
		assertFalse(Arrays.equals(plain, scrambled));
		assertArrayEquals(plain, BinaryScrambler.restore(scrambled, key));
	}
}
