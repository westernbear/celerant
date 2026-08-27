package io.github.westernbear.celerant.client.physics;

/**
 * Macklin XPBD projections for Line BoneCloth stretch and bend (chord) constraints.
 */
public final class XpbdSolver {
	private XpbdSolver() {
	}

	/**
	 * Distance constraint between particles {@code i} and {@code j}.
	 * Positions are interleaved xyz in {@code x}; inverse masses in {@code w}; λ in {@code lambda} at {@code lambdaIndex}.
	 */
	public static void projectStretch(float[] x, float[] w, int i, int j, float restLength, float compliance,
		float dt, float[] lambda, int lambdaIndex) {
		float wi = w[i];
		float wj = w[j];
		float wSum = wi + wj;
		if (wSum <= 0.0F || dt <= 0.0F) {
			return;
		}
		int ia = i * 3;
		int ja = j * 3;
		float dx = x[ja] - x[ia];
		float dy = x[ja + 1] - x[ia + 1];
		float dz = x[ja + 2] - x[ia + 2];
		float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (dist < 1.0E-8F) {
			return;
		}
		float nx = dx / dist;
		float ny = dy / dist;
		float nz = dz / dist;
		float c = dist - restLength;
		float alphaTilde = compliance / (dt * dt);
		float dl = (-c - alphaTilde * lambda[lambdaIndex]) / (wSum + alphaTilde);
		lambda[lambdaIndex] += dl;
		x[ia] -= wi * dl * nx;
		x[ia + 1] -= wi * dl * ny;
		x[ia + 2] -= wi * dl * nz;
		x[ja] += wj * dl * nx;
		x[ja + 1] += wj * dl * ny;
		x[ja + 2] += wj * dl * nz;
	}

	/**
	 * Bend as a chord distance between particles {@code i} and {@code k} (skipping middle).
	 */
	public static void projectBend(float[] x, float[] w, int i, int k, float restChord, float compliance, float dt,
		float[] lambda, int lambdaIndex) {
		projectStretch(x, w, i, k, restChord, compliance, dt, lambda, lambdaIndex);
	}

	public static float complianceFromStiffness(float stiffness) {
		float s = Math.max(stiffness, 1.0E-4F);
		return 1.0F / s;
	}

	public static float bendCompliance(float stretchCompliance) {
		return stretchCompliance * 4.0F;
	}
}
