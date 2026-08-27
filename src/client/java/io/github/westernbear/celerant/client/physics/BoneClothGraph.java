package io.github.westernbear.celerant.client.physics;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import de.javagl.jgltf.model.NodeModel;

/**
 * Magica BoneCloth Line chain simulated with XPBD (particles at joint origins).
 */
public final class BoneClothGraph {
	private static final int SUBSTEPS = 2;
	private static final int ITERATIONS = 4;
	private static final float DEFAULT_TIP_LENGTH = 0.07F;

	private final NodeModel[] nodes;
	private final boolean[] writable;
	private final float[] invMass;
	private final float[] x;
	private final float[] xPrev;
	private final float[] restStretch;
	private final float[] restBend;
	private final float[] stretchCompliance;
	private final float[] bendCompliance;
	private final float[] drag;
	private final float[] gravityPower;
	private final float[] gravityDir;
	private final float[] hitRadius;
	private final float[] restRotation;
	private final float[] boneAxis;
	private final float[] boneLength;
	private final float[] stretchLambda;
	private final float[] bendLambda;
	private final List<BoundCollider> colliders;
	private final NodeModel center;
	private final Matrix4f centerWorld = new Matrix4f();
	private final Matrix4f centerWorldInv = new Matrix4f();
	private final Matrix4f tmpMatrix = new Matrix4f();
	private final Matrix4f parentWorld = new Matrix4f();
	private final Matrix4f initialLocal = new Matrix4f();
	private final float[] matScratch = new float[16];
	private final Vector3f tmp = new Vector3f();
	private final Vector3f tmp2 = new Vector3f();
	private final Quaternionf tmpQuat = new Quaternionf();
	private boolean initialized;

	BoneClothGraph(NodeModel[] nodes, boolean[] writable, float[] invMass, float[] restStretch, float[] restBend,
		float[] stretchCompliance, float[] bendCompliance, float[] drag, float[] gravityPower, float[] gravityDir,
		float[] hitRadius, float[] restRotation, float[] boneAxis, float[] boneLength, List<BoundCollider> colliders,
		NodeModel center) {
		this.nodes = nodes;
		this.writable = writable;
		this.invMass = invMass;
		this.restStretch = restStretch;
		this.restBend = restBend;
		this.stretchCompliance = stretchCompliance;
		this.bendCompliance = bendCompliance;
		this.drag = drag;
		this.gravityPower = gravityPower;
		this.gravityDir = gravityDir;
		this.hitRadius = hitRadius;
		this.restRotation = restRotation;
		this.boneAxis = boneAxis;
		this.boneLength = boneLength;
		this.colliders = List.copyOf(colliders);
		this.center = center;
		int n = nodes.length;
		this.x = new float[n * 3];
		this.xPrev = new float[n * 3];
		this.stretchLambda = new float[Math.max(0, n - 1)];
		this.bendLambda = new float[Math.max(0, n - 2)];
	}

	int particleCount() {
		return nodes.length;
	}

	void reset() {
		initialized = false;
	}

	void restoreRest() {
		restoreRestRotations();
		initialized = false;
	}

	void step(float dt) {
		if (nodes.length < 2) {
			return;
		}
		restoreRestRotations();
		if (dt <= 0.0F) {
			return;
		}
		float clamped = Math.min(0.05F, Math.max(1.0E-4F, dt));
		float subDt = clamped / SUBSTEPS;
		if (!initialized) {
			captureInitialPositions();
			initialized = true;
		}
		for (int sub = 0; sub < SUBSTEPS; sub++) {
			pinKinematicFromNodes();
			predict(subDt);
			pinKinematicFromNodes();
			java.util.Arrays.fill(stretchLambda, 0.0F);
			java.util.Arrays.fill(bendLambda, 0.0F);
			for (int it = 0; it < ITERATIONS; it++) {
				for (int i = 0; i < restStretch.length; i++) {
					XpbdSolver.projectStretch(x, invMass, i, i + 1, restStretch[i], stretchCompliance[i], subDt,
						stretchLambda, i);
				}
				for (int i = 0; i < restBend.length; i++) {
					XpbdSolver.projectBend(x, invMass, i, i + 2, restBend[i], bendCompliance[i], subDt, bendLambda, i);
				}
				collide(subDt);
			}
			writeRotations();
		}
	}

	private void restoreRestRotations() {
		for (int i = 0; i < nodes.length; i++) {
			if (nodes[i] == null || !writable[i]) {
				continue;
			}
			int o = i * 4;
			nodes[i].setRotation(new float[] {restRotation[o], restRotation[o + 1], restRotation[o + 2],
				restRotation[o + 3]});
		}
	}

	private void captureInitialPositions() {
		updateCenter();
		for (int i = 0; i < nodes.length; i++) {
			worldParticle(i, tmp);
			toCenter(tmp);
			int o = i * 3;
			x[o] = tmp.x;
			x[o + 1] = tmp.y;
			x[o + 2] = tmp.z;
			xPrev[o] = tmp.x;
			xPrev[o + 1] = tmp.y;
			xPrev[o + 2] = tmp.z;
		}
	}

	private void pinKinematicFromNodes() {
		updateCenter();
		for (int i = 0; i < nodes.length; i++) {
			if (invMass[i] > 0.0F) {
				continue;
			}
			worldParticle(i, tmp);
			toCenter(tmp);
			int o = i * 3;
			x[o] = tmp.x;
			x[o + 1] = tmp.y;
			x[o + 2] = tmp.z;
			xPrev[o] = tmp.x;
			xPrev[o + 1] = tmp.y;
			xPrev[o + 2] = tmp.z;
		}
	}

	private void predict(float dt) {
		updateCenter();
		for (int i = 0; i < nodes.length; i++) {
			if (invMass[i] <= 0.0F) {
				continue;
			}
			int o = i * 3;
			float vx = (x[o] - xPrev[o]) * (1.0F - drag[i]);
			float vy = (x[o + 1] - xPrev[o + 1]) * (1.0F - drag[i]);
			float vz = (x[o + 2] - xPrev[o + 2]) * (1.0F - drag[i]);
			xPrev[o] = x[o];
			xPrev[o + 1] = x[o + 1];
			xPrev[o + 2] = x[o + 2];
			float gx = gravityDir[i * 3];
			float gy = gravityDir[i * 3 + 1];
			float gz = gravityDir[i * 3 + 2];
			if (center != null) {
				tmp.set(gx, gy, gz);
				centerWorldInv.transformDirection(tmp);
				gx = tmp.x;
				gy = tmp.y;
				gz = tmp.z;
			}
			float gScale = gravityPower[i] * dt;
			x[o] += vx + gx * gScale;
			x[o + 1] += vy + gy * gScale;
			x[o + 2] += vz + gz * gScale;
		}
	}

	private void collide(float dt) {
		if (colliders.isEmpty()) {
			return;
		}
		updateCenter();
		for (BoundCollider bound : colliders) {
			tmpMatrix.set(bound.node().computeGlobalTransform(matScratch));
			for (int i = 0; i < nodes.length; i++) {
				if (invMass[i] <= 0.0F) {
					continue;
				}
				int o = i * 3;
				fromCenter(tmp.set(x[o], x[o + 1], x[o + 2]));
				if (bound.collider().pushOut(tmpMatrix, tmp.x, tmp.y, tmp.z, hitRadius[i], tmp2, tmp)) {
					toCenter(tmp2);
					x[o] = tmp2.x;
					x[o + 1] = tmp2.y;
					x[o + 2] = tmp2.z;
					if (i > 0) {
						XpbdSolver.projectStretch(x, invMass, i - 1, i, restStretch[i - 1], 0.0F, dt, stretchLambda,
							i - 1);
					}
				}
			}
		}
	}

	private void writeRotations() {
		updateCenter();
		for (int i = 0; i < nodes.length - 1; i++) {
			NodeModel bone = nodes[i];
			if (bone == null || !writable[i]) {
				continue;
			}
			if (bone.getParent() != null) {
				parentWorld.set(bone.getParent().computeGlobalTransform(matScratch));
			} else {
				parentWorld.identity();
			}
			int ro = i * 4;
			Quaternionf rest = new Quaternionf(restRotation[ro], restRotation[ro + 1], restRotation[ro + 2],
				restRotation[ro + 3]);
			initialLocal.translationRotateScale(translationOf(bone), rest, scaleOf(bone));
			Matrix4f worldInitialInv = new Matrix4f(parentWorld).mul(initialLocal).invert();
			int to = (i + 1) * 3;
			fromCenter(tmp.set(x[to], x[to + 1], x[to + 2]));
			worldInitialInv.transformPosition(tmp);
			if (tmp.lengthSquared() < 1.0E-12F) {
				continue;
			}
			tmp.normalize();
			int ao = i * 3;
			tmpQuat.rotationTo(boneAxis[ao], boneAxis[ao + 1], boneAxis[ao + 2], tmp.x, tmp.y, tmp.z);
			Quaternionf out = new Quaternionf(rest).mul(tmpQuat);
			bone.setRotation(new float[] {out.x, out.y, out.z, out.w});
		}
	}

	private void worldParticle(int index, Vector3f out) {
		NodeModel node = nodes[index];
		if (node != null) {
			tmpMatrix.set(node.computeGlobalTransform(matScratch));
			out.set(tmpMatrix.m30(), tmpMatrix.m31(), tmpMatrix.m32());
			return;
		}
		NodeModel parent = nodes[index - 1];
		tmpMatrix.set(parent.computeGlobalTransform(matScratch));
		int ao = (index - 1) * 3;
		out.set(boneAxis[ao], boneAxis[ao + 1], boneAxis[ao + 2]).mul(boneLength[index - 1]);
		tmpMatrix.transformPosition(out);
	}

	private void updateCenter() {
		if (center == null) {
			centerWorld.identity();
			centerWorldInv.identity();
			return;
		}
		centerWorld.set(center.computeGlobalTransform(matScratch));
		centerWorld.invert(centerWorldInv);
	}

	private void toCenter(Vector3f v) {
		if (center != null) {
			centerWorldInv.transformPosition(v);
		}
	}

	private void fromCenter(Vector3f v) {
		if (center != null) {
			centerWorld.transformPosition(v);
		}
	}

	private static Vector3f translationOf(NodeModel node) {
		float[] t = node.getTranslation();
		if (t != null && t.length >= 3) {
			return new Vector3f(t[0], t[1], t[2]);
		}
		return new Vector3f();
	}

	private static Vector3f scaleOf(NodeModel node) {
		float[] s = node.getScale();
		if (s != null && s.length >= 3) {
			return new Vector3f(s[0], s[1], s[2]);
		}
		return new Vector3f(1.0F, 1.0F, 1.0F);
	}

	record BoundCollider(NodeModel node, SpringBoneCollider collider) {
		BoundCollider {
			Objects.requireNonNull(node);
			Objects.requireNonNull(collider);
		}
	}

	static BoneClothGraph build(List<NodeModel> jointNodes, float[] stiffness, float[] dragForce,
		float[] gravityPowerIn, float[][] gravityDirIn, float[] hitRadiusIn, List<BoundCollider> colliders,
		NodeModel center, Set<NodeModel> humanoidNodes) {
		int jointCount = jointNodes.size();
		if (jointCount < 1) {
			throw new IllegalArgumentException("spring needs at least one joint");
		}
		int particleCount = jointCount + 1;
		NodeModel[] nodes = new NodeModel[particleCount];
		boolean[] writable = new boolean[particleCount];
		float[] invMass = new float[particleCount];
		float[] restStretch = new float[particleCount - 1];
		float[] restBend = new float[Math.max(0, particleCount - 2)];
		float[] stretchCompliance = new float[particleCount - 1];
		float[] bendCompliance = new float[Math.max(0, particleCount - 2)];
		float[] drag = new float[particleCount];
		float[] gPower = new float[particleCount];
		float[] gDir = new float[particleCount * 3];
		float[] radii = new float[particleCount];
		float[] restRotation = new float[particleCount * 4];
		float[] boneAxis = new float[particleCount * 3];
		float[] boneLength = new float[particleCount];

		for (int i = 0; i < jointCount; i++) {
			nodes[i] = jointNodes.get(i);
			writable[i] = nodes[i] != null && !humanoidNodes.contains(nodes[i]);
			invMass[i] = i == 0 ? 0.0F : 1.0F;
			float stiff = stiffness[Math.min(i, stiffness.length - 1)];
			float d = dragForce[Math.min(i, dragForce.length - 1)];
			float gp = gravityPowerIn[Math.min(i, gravityPowerIn.length - 1)];
			float[] gd = gravityDirIn[Math.min(i, gravityDirIn.length - 1)];
			float hr = hitRadiusIn[Math.min(i, hitRadiusIn.length - 1)];
			drag[i] = clamp01(d);
			gPower[i] = gp;
			gDir[i * 3] = gd[0];
			gDir[i * 3 + 1] = gd[1];
			gDir[i * 3 + 2] = gd[2];
			radii[i] = Math.max(0.0F, hr);
			captureRest(nodes[i], i, restRotation, boneAxis, boneLength, jointNodes, i == jointCount - 1);
			stretchCompliance[Math.max(0, i)] = XpbdSolver.complianceFromStiffness(stiff);
		}
		int tip = particleCount - 1;
		nodes[tip] = null;
		writable[tip] = false;
		invMass[tip] = 1.0F;
		drag[tip] = drag[jointCount - 1];
		gPower[tip] = gPower[jointCount - 1];
		System.arraycopy(gDir, (jointCount - 1) * 3, gDir, tip * 3, 3);
		radii[tip] = radii[jointCount - 1];
		stretchCompliance[jointCount - 1] = XpbdSolver.complianceFromStiffness(
			stiffness[Math.min(jointCount - 1, stiffness.length - 1)]);

		float[] restX = new float[particleCount * 3];
		for (int i = 0; i < jointCount; i++) {
			Vector3f p = worldPosition(nodes[i]);
			restX[i * 3] = p.x;
			restX[i * 3 + 1] = p.y;
			restX[i * 3 + 2] = p.z;
		}
		Matrix4f lastWorld = new Matrix4f().set(nodes[jointCount - 1].computeGlobalTransform(new float[16]));
		Vector3f tipPos = new Vector3f(restX[(jointCount - 1) * 3], restX[(jointCount - 1) * 3 + 1],
			restX[(jointCount - 1) * 3 + 2]);
		Vector3f axis = new Vector3f(boneAxis[(jointCount - 1) * 3], boneAxis[(jointCount - 1) * 3 + 1],
			boneAxis[(jointCount - 1) * 3 + 2]).mul(boneLength[jointCount - 1]);
		lastWorld.transformDirection(axis);
		tipPos.add(axis);
		restX[tip * 3] = tipPos.x;
		restX[tip * 3 + 1] = tipPos.y;
		restX[tip * 3 + 2] = tipPos.z;

		for (int i = 0; i < particleCount - 1; i++) {
			restStretch[i] = distance(restX, i, i + 1);
			if (restStretch[i] < 1.0E-5F) {
				restStretch[i] = DEFAULT_TIP_LENGTH;
			}
		}
		for (int i = 0; i < particleCount - 2; i++) {
			restBend[i] = distance(restX, i, i + 2);
			bendCompliance[i] = XpbdSolver.bendCompliance(stretchCompliance[Math.min(i, stretchCompliance.length - 1)]);
		}

		return new BoneClothGraph(nodes, writable, invMass, restStretch, restBend, stretchCompliance, bendCompliance,
			drag, gPower, gDir, radii, restRotation, boneAxis, boneLength,
			colliders == null ? List.of() : new ArrayList<>(colliders), center);
	}

	private static void captureRest(NodeModel node, int index, float[] restRotation, float[] boneAxis,
		float[] boneLength, List<NodeModel> joints, boolean lastJoint) {
		float[] rot = node.getRotation();
		if (rot == null || rot.length < 4) {
			rot = new float[] {0.0F, 0.0F, 0.0F, 1.0F};
		}
		Quaternionf q = new Quaternionf(rot[0], rot[1], rot[2], rot[3]).normalize();
		int ro = index * 4;
		restRotation[ro] = q.x;
		restRotation[ro + 1] = q.y;
		restRotation[ro + 2] = q.z;
		restRotation[ro + 3] = q.w;

		Vector3f axis = new Vector3f(0.0F, 1.0F, 0.0F);
		float length = DEFAULT_TIP_LENGTH;
		if (!lastJoint && index + 1 < joints.size()) {
			float[] childT = joints.get(index + 1).getTranslation();
			if (childT != null && childT.length >= 3) {
				axis.set(childT[0], childT[1], childT[2]);
				length = axis.length();
				if (length > 1.0E-6F) {
					axis.mul(1.0F / length);
				} else {
					axis.set(0.0F, 1.0F, 0.0F);
					length = DEFAULT_TIP_LENGTH;
				}
			}
		} else {
			List<NodeModel> children = node.getChildren();
			if (children != null && !children.isEmpty()) {
				float[] childT = children.get(0).getTranslation();
				if (childT != null && childT.length >= 3) {
					axis.set(childT[0], childT[1], childT[2]);
					length = axis.length();
					if (length > 1.0E-6F) {
						axis.mul(1.0F / length);
					} else {
						axis.set(0.0F, 1.0F, 0.0F);
						length = DEFAULT_TIP_LENGTH;
					}
				}
			} else {
				float[] t = node.getTranslation();
				if (t != null && t.length >= 3 && t[0] * t[0] + t[1] * t[1] + t[2] * t[2] > 1.0E-8F) {
					axis.set(t[0], t[1], t[2]).normalize();
				}
			}
		}
		int ao = index * 3;
		boneAxis[ao] = axis.x;
		boneAxis[ao + 1] = axis.y;
		boneAxis[ao + 2] = axis.z;
		boneLength[index] = length;
	}

	private static Vector3f worldPosition(NodeModel node) {
		float[] m = node.computeGlobalTransform(new float[16]);
		return new Vector3f(m[12], m[13], m[14]);
	}

	private static float distance(float[] x, int i, int j) {
		int a = i * 3;
		int b = j * 3;
		float dx = x[b] - x[a];
		float dy = x[b + 1] - x[a + 1];
		float dz = x[b + 2] - x[a + 2];
		return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private static float clamp01(float v) {
		return Math.min(1.0F, Math.max(0.0F, v));
	}
}
