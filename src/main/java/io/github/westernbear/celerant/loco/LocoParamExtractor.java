package io.github.westernbear.celerant.loco;

/**
 * Maps Minecraft-like motion inputs to {@link LocoParams} without depending on game classes.
 */
public final class LocoParamExtractor {
	private LocoParamExtractor() {
	}

	public static LocoParams from(
		float moveX,
		float moveY,
		float moveZ,
		boolean onGround,
		boolean crouching,
		boolean sprinting,
		float timeSeconds
	) {
		float magnitude = (float) Math.sqrt(moveX * moveX + moveZ * moveZ);
		boolean inAir = !onGround;
		float upright = crouching ? 0.55F : (inAir ? 0.85F : 1.0F);
		return new LocoParams(moveX, moveY, moveZ, magnitude, onGround, crouching, inAir, sprinting,
			upright, timeSeconds);
	}
}
