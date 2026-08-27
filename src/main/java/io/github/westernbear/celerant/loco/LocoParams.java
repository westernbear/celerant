package io.github.westernbear.celerant.loco;

/**
 * Compact locomotion snapshot shared by local evaluation and plugin sync.
 */
public record LocoParams(
	float velocityX,
	float velocityY,
	float velocityZ,
	float velocityMagnitude,
	boolean grounded,
	boolean crouching,
	boolean inAir,
	boolean sprinting,
	float upright,
	float timeSeconds
) {
	public static final LocoParams IDLE = new LocoParams(0, 0, 0, 0, true, false, false, false, 1.0F, 0);

	public byte[] toBytes() {
		java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(29).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		buf.putFloat(velocityX);
		buf.putFloat(velocityY);
		buf.putFloat(velocityZ);
		buf.putFloat(velocityMagnitude);
		buf.putFloat(upright);
		buf.putFloat(timeSeconds);
		byte flags = 0;
		if (grounded) {
			flags |= 1;
		}
		if (crouching) {
			flags |= 2;
		}
		if (inAir) {
			flags |= 4;
		}
		if (sprinting) {
			flags |= 8;
		}
		buf.put(flags);
		return buf.array();
	}

	public static LocoParams fromBytes(byte[] data) {
		if (data == null || data.length < 29) {
			return IDLE;
		}
		java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		float vx = buf.getFloat();
		float vy = buf.getFloat();
		float vz = buf.getFloat();
		float mag = buf.getFloat();
		float upright = buf.getFloat();
		float time = buf.getFloat();
		byte flags = buf.get();
		return new LocoParams(vx, vy, vz, mag,
			(flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0,
			upright, time);
	}
}
