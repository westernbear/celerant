package io.github.westernbear.celerant.client.physics;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * VRM spring-bone collider (sphere or capsule) evaluated in world space.
 */
public final class SpringBoneCollider {
	public enum Shape {
		SPHERE,
		CAPSULE
	}

	private final Shape shape;
	private final float radius;
	private final float ox;
	private final float oy;
	private final float oz;
	private final float tx;
	private final float ty;
	private final float tz;

	private SpringBoneCollider(Shape shape, float radius, float ox, float oy, float oz, float tx, float ty, float tz) {
		this.shape = shape;
		this.radius = Math.max(0.0F, radius);
		this.ox = ox;
		this.oy = oy;
		this.oz = oz;
		this.tx = tx;
		this.ty = ty;
		this.tz = tz;
	}

	public static SpringBoneCollider sphere(float offsetX, float offsetY, float offsetZ, float radius) {
		return new SpringBoneCollider(Shape.SPHERE, radius, offsetX, offsetY, offsetZ, 0.0F, 0.0F, 0.0F);
	}

	public static SpringBoneCollider capsule(float offsetX, float offsetY, float offsetZ, float tailX, float tailY,
		float tailZ, float radius) {
		return new SpringBoneCollider(Shape.CAPSULE, radius, offsetX, offsetY, offsetZ, tailX, tailY, tailZ);
	}

	public Shape shape() {
		return shape;
	}

	public float radius() {
		return radius;
	}

	/**
	 * If the particle (with {@code hitRadius}) penetrates, push it out and return true.
	 * {@code normalOut} receives the outward unit normal when penetration occurs.
	 */
	public boolean pushOut(Matrix4f nodeWorld, float px, float py, float pz, float hitRadius, Vector3f positionOut,
		Vector3f normalOut) {
		float combined = radius + Math.max(0.0F, hitRadius);
		if (shape == Shape.SPHERE) {
			float cx = nodeWorld.m00() * ox + nodeWorld.m10() * oy + nodeWorld.m20() * oz + nodeWorld.m30();
			float cy = nodeWorld.m01() * ox + nodeWorld.m11() * oy + nodeWorld.m21() * oz + nodeWorld.m31();
			float cz = nodeWorld.m02() * ox + nodeWorld.m12() * oy + nodeWorld.m22() * oz + nodeWorld.m32();
			float dx = px - cx;
			float dy = py - cy;
			float dz = pz - cz;
			float distSq = dx * dx + dy * dy + dz * dz;
			if (distSq >= combined * combined) {
				return false;
			}
			float dist = (float) Math.sqrt(Math.max(distSq, 1.0E-12F));
			float nx = dx / dist;
			float ny = dy / dist;
			float nz = dz / dist;
			float penetration = combined - dist;
			positionOut.set(px + nx * penetration, py + ny * penetration, pz + nz * penetration);
			normalOut.set(nx, ny, nz);
			return true;
		}

		float ax = nodeWorld.m00() * ox + nodeWorld.m10() * oy + nodeWorld.m20() * oz + nodeWorld.m30();
		float ay = nodeWorld.m01() * ox + nodeWorld.m11() * oy + nodeWorld.m21() * oz + nodeWorld.m31();
		float az = nodeWorld.m02() * ox + nodeWorld.m12() * oy + nodeWorld.m22() * oz + nodeWorld.m32();
		float bx = nodeWorld.m00() * tx + nodeWorld.m10() * ty + nodeWorld.m20() * tz + nodeWorld.m30();
		float by = nodeWorld.m01() * tx + nodeWorld.m11() * ty + nodeWorld.m21() * tz + nodeWorld.m31();
		float bz = nodeWorld.m02() * tx + nodeWorld.m12() * ty + nodeWorld.m22() * tz + nodeWorld.m32();
		float abx = bx - ax;
		float aby = by - ay;
		float abz = bz - az;
		float abLenSq = abx * abx + aby * aby + abz * abz;
		float t = 0.0F;
		if (abLenSq > 1.0E-12F) {
			t = ((px - ax) * abx + (py - ay) * aby + (pz - az) * abz) / abLenSq;
			t = Math.min(1.0F, Math.max(0.0F, t));
		}
		float cx = ax + abx * t;
		float cy = ay + aby * t;
		float cz = az + abz * t;
		float dx = px - cx;
		float dy = py - cy;
		float dz = pz - cz;
		float distSq = dx * dx + dy * dy + dz * dz;
		if (distSq >= combined * combined) {
			return false;
		}
		float dist = (float) Math.sqrt(Math.max(distSq, 1.0E-12F));
		float nx = dx / dist;
		float ny = dy / dist;
		float nz = dz / dist;
		float penetration = combined - dist;
		positionOut.set(px + nx * penetration, py + ny * penetration, pz + nz * penetration);
		normalOut.set(nx, ny, nz);
		return true;
	}
}
