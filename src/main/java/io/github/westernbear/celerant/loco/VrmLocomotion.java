package io.github.westernbear.celerant.loco;

import java.util.Map;

/**
 * Facade for L3 locomotion evaluation used by client runtime and remote sync.
 */
public final class VrmLocomotion {
	private static volatile boolean locomotionEnabled = true;
	private static volatile boolean breathingEnabled = true;
	private static volatile boolean swayingEnabled = true;

	private VrmLocomotion() {
	}

	public static void setLocomotionEnabled(boolean enabled) {
		locomotionEnabled = enabled;
	}

	public static void setBreathingEnabled(boolean enabled) {
		breathingEnabled = enabled;
	}

	public static void setSwayingEnabled(boolean enabled) {
		swayingEnabled = enabled;
	}

	public static boolean locomotionEnabled() {
		return locomotionEnabled;
	}

	public static boolean breathingEnabled() {
		return breathingEnabled;
	}

	public static boolean swayingEnabled() {
		return swayingEnabled;
	}

	/** Procedural catalog always provides clips. */
	public static boolean hasClips() {
		return true;
	}

	public static Map<String, float[]> evaluate(LocoParams params) {
		return LocoLayerMixer.mix(params, locomotionEnabled, breathingEnabled, swayingEnabled);
	}
}
