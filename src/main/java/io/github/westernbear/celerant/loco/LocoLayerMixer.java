package io.github.westernbear.celerant.loco;

import java.util.Map;

/**
 * Warudo-style layering under VRChat-style locomotion weights.
 */
public final class LocoLayerMixer {
	private LocoLayerMixer() {
	}

	public static Map<String, float[]> mix(
		LocoParams params,
		boolean locomotionEnabled,
		boolean breathingEnabled,
		boolean swayingEnabled
	) {
		if (!locomotionEnabled) {
			return Map.of();
		}
		LocoClipCatalog.Weights weights = LocoClipCatalog.weights(params, breathingEnabled, swayingEnabled);
		return LocoClipCatalog.boneEulerDeltas(params, weights);
	}
}
