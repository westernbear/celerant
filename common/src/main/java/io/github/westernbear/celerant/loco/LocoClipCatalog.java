package io.github.westernbear.celerant.loco;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Procedural clip weights approximating VRChat Base + Warudo Idle/Breathing/Swaying.
 * Does not embed copyrighted VRChat proxy animation binaries.
 */
public final class LocoClipCatalog {
	public record Weights(
		float idle,
		float walk,
		float run,
		float crouch,
		float breathe,
		float sway,
		float air
	) {
	}

	private LocoClipCatalog() {
	}

	public static Weights weights(LocoParams params, boolean breathingEnabled, boolean swayingEnabled) {
		float mag = params.velocityMagnitude();
		float walk = 0.0F;
		float run = 0.0F;
		float idle = 0.0F;
		if (params.grounded() && !params.crouching()) {
			if (mag < 0.02F) {
				idle = 1.0F;
			} else if (params.sprinting() || mag > 0.22F) {
				run = clamp01((mag - 0.12F) / 0.25F);
				walk = 1.0F - run;
			} else {
				walk = clamp01(mag / 0.18F);
				idle = 1.0F - walk;
			}
		} else if (params.grounded() && params.crouching()) {
			walk = clamp01(mag / 0.12F);
			idle = 1.0F - walk;
		}
		float crouch = params.crouching() ? 1.0F : 0.0F;
		float air = params.inAir() ? 1.0F : 0.0F;
		float breathe = breathingEnabled && params.grounded() && mag < 0.05F ? 1.0F : 0.0F;
		float sway = swayingEnabled && params.grounded() ? 0.35F * (1.0F - clamp01(mag / 0.2F)) : 0.0F;
		return new Weights(idle, walk, run, crouch, breathe, sway, air);
	}

	/**
	 * Bone name → XYZ Euler deltas in radians to multiply onto rest pose.
	 */
	public static Map<String, float[]> boneEulerDeltas(LocoParams params, Weights w) {
		Map<String, float[]> out = new LinkedHashMap<>();
		float t = params.timeSeconds();
		float phase = t * (6.5F + 4.0F * w.run() + 2.0F * w.walk());
		float swing = (float) Math.sin(phase);
		float swingOpp = -swing;
		float walkAmp = 0.55F * w.walk() + 0.85F * w.run();
		float crouchAmp = 0.25F * w.crouch();

		add(out, "leftUpperLeg", walkAmp * swing, 0, 0);
		add(out, "rightUpperLeg", walkAmp * swingOpp, 0, 0);
		add(out, "leftLowerLeg", Math.max(0, -walkAmp * swing) * 0.9F, 0, 0);
		add(out, "rightLowerLeg", Math.max(0, -walkAmp * swingOpp) * 0.9F, 0, 0);
		add(out, "leftUpperArm", -walkAmp * swing * 0.7F, 0, -0.05F);
		add(out, "rightUpperArm", -walkAmp * swingOpp * 0.7F, 0, 0.05F);

		float breath = w.breathe() * 0.04F * (float) Math.sin(t * 2.2F);
		add(out, "spine", breath, 0, 0);
		add(out, "chest", breath * 0.6F, 0, 0);

		float sway = w.sway() * 0.03F * (float) Math.sin(t * 1.1F);
		add(out, "hips", 0, sway, 0);

		if (w.crouch() > 0.0F) {
			add(out, "spine", 0.35F * w.crouch(), 0, 0);
			add(out, "leftUpperLeg", 0.55F * w.crouch() + crouchAmp, 0, 0);
			add(out, "rightUpperLeg", 0.55F * w.crouch() + crouchAmp, 0, 0);
		}

		if (w.air() > 0.0F) {
			float rise = params.velocityY() > 0 ? 1.0F : 0.0F;
			float fall = params.velocityY() < 0 ? 1.0F : 0.35F;
			add(out, "leftUpperArm", (-0.65F * rise - 0.10F * fall) * w.air(), 0, (-0.15F * rise - 0.5F * fall) * w.air());
			add(out, "rightUpperArm", (-0.65F * rise - 0.10F * fall) * w.air(), 0, (0.15F * rise + 0.5F * fall) * w.air());
			add(out, "leftUpperLeg", (-1.0F * rise + 0.22F * fall) * w.air(), 0, 0);
			add(out, "rightUpperLeg", (0.25F * rise + 0.22F * fall) * w.air(), 0, 0);
		}

		if (w.idle() > 0.5F && walkAmp < 0.05F) {
			add(out, "leftUpperArm", 0, 0, -0.08F * w.idle());
			add(out, "rightUpperArm", 0, 0, 0.08F * w.idle());
		}
		return Collections.unmodifiableMap(out);
	}

	private static void add(Map<String, float[]> out, String bone, float x, float y, float z) {
		float[] prev = out.get(bone);
		if (prev == null) {
			out.put(bone, new float[] {x, y, z});
			return;
		}
		prev[0] += x;
		prev[1] += y;
		prev[2] += z;
	}

	private static float clamp01(float v) {
		return Math.max(0.0F, Math.min(1.0F, v));
	}
}
