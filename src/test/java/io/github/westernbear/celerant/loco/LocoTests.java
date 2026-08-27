package io.github.westernbear.celerant.loco;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class LocoParamExtractorTest {
	@Test
	void extractsMagnitudeAndFlags() {
		LocoParams p = LocoParamExtractor.from(0.3F, 0.0F, 0.4F, true, false, true, 1.0F);
		assertEquals(0.5F, p.velocityMagnitude(), 1e-5);
		assertTrue(p.grounded());
		assertTrue(p.sprinting());
		assertFalse(p.inAir());
		assertFalse(p.crouching());
	}

	@Test
	void roundTripBytes() {
		LocoParams p = LocoParamExtractor.from(0.1F, -0.2F, 0.0F, false, true, false, 3.5F);
		LocoParams q = LocoParams.fromBytes(p.toBytes());
		assertEquals(p.velocityX(), q.velocityX(), 1e-5);
		assertEquals(p.crouching(), q.crouching());
		assertEquals(p.inAir(), q.inAir());
	}
}

class LocoLayerMixerTest {
	@Test
	void idleBreathingProducesSpineDelta() {
		LocoParams idle = LocoParamExtractor.from(0, 0, 0, true, false, false, 1.0F);
		Map<String, float[]> deltas = LocoLayerMixer.mix(idle, true, true, true);
		assertTrue(deltas.containsKey("spine") || deltas.containsKey("hips")
			|| deltas.containsKey("leftUpperArm"));
	}

	@Test
	void walkProducesLegSwing() {
		LocoParams walk = LocoParamExtractor.from(0.15F, 0, 0, true, false, false, 2.0F);
		Map<String, float[]> deltas = LocoLayerMixer.mix(walk, true, false, false);
		assertTrue(deltas.containsKey("leftUpperLeg"));
		assertTrue(deltas.containsKey("rightUpperLeg"));
	}
}
