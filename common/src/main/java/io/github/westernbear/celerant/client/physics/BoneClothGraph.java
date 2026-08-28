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
 * VRM spring-bone Line chain using the UniVRM / three-vrm Verlet reference
 * (inertia + rest-axis stiffness + gravity + length constraint + colliders).
 */
public final class BoneClothGraph {
	private static final float DEFAULT_TIP_LENGTH = 0.07F;

	private final NodeModel[] nodes;
	private final boolean[] writable;
	private final float[] stiffness;
	private final float[] drag;
	private final float[] gravityPower;
	private final float[] gravityDir;
	private final float[] hitRadius;
	private final float[] restRotation;
	private final float[] boneAxis;
	private final float[] boneLength;
	/** Center-space tail positions for joint {@code i} live at particle {@code i + 1}. */
	private final float[] x;
	private final float[] xPrev;
	private final List<BoundCollider> colliders;
	private final NodeModel center;
	private final Matrix4f centerWorld = new Matrix4f();
	private final Matrix4f centerWorldInv = new Matrix4f();
	private final Matrix4f tmpMatrix = new Matrix4f();
	private final Matrix4f parentWorld = new Matrix4f();
	private final Matrix4f initialLocal = new Matrix4f();
	private final Matrix4f worldInitial = new Matrix4f();
	private final float[] matScratch = new float[16];
	private final Vector3f tmp = new Vector3f();
	private final Vector3f tmp2 = new Vector3f();
	private final Vector3f head = new Vector3f();
	private final Vector3f nextTail = new Vector3f();
	private final Quaternionf tmpQuat = new Quaternionf();
	private final Quaternionf restQuat = new Quaternionf();
	private final Quaternionf outQuat = new Quaternionf();
	private final Vector3f translationScratch = new Vector3f();
	private final Vector3f scaleScratch = new Vector3f(1.0F, 1.0F, 1.0F);
	/** Stable per-joint rotation arrays — {@code NodeModel#setRotation} stores the reference. */
	private final float[][] restRotationSlots;
	private final float[][] writeRotationSlots;
	private boolean initialized;

	BoneClothGraph(NodeModel[] nodes, boolean[] writable, float[] stiffness, float[] drag, float[] gravityPower,
		float[] gravityDir, float[] hitRadius, float[] restRotation, float[] boneAxis, float[] boneLength,
		List<BoundCollider> colliders, NodeModel center) {
		this.nodes = nodes;
		this.writable = writable;
		this.stiffness = stiffness;
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
		this.restRotationSlots = new float[n][];
		this.writeRotationSlots = new float[n][];
		for (int i = 0; i < n; i++) {
			int o = i * 4;
			restRotationSlots[i] = new float[] {restRotation[o], restRotation[o + 1], restRotation[o + 2],
				restRotation[o + 3]};
			writeRotationSlots[i] = new float[4];
		}
	}

	int particleCount() {
		return nodes.length;
	}

	/** True when every joint node in this chain is present in {@code culled}. */
	boolean isFullyCulled(Set<NodeModel> culled) {
		boolean any = false;
		for (NodeModel node : nodes) {
			if (node == null) {
				continue;
			}
			any = true;
			if (!culled.contains(node)) {
				return false;
			}
		}
		return any;
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
		if (dt <= 0.0F) {
			restoreRestRotations();
			return;
		}
		float clamped = Math.min(0.05F, Math.max(1.0E-4F, dt));
		restoreRestRotations();
		updateCenter();
		if (!initialized) {
			captureInitialTails();
			initialized = true;
		}
		// Root → tip so each child sees parent spring rotations already written.
		for (int joint = 0; joint < nodes.length - 1; joint++) {
			if (nodes[joint] == null || !writable[joint]) {
				continue;
			}
			updateJoint(joint, clamped);
		}
	}

	private void restoreRestRotations() {
		for (int i = 0; i < nodes.length; i++) {
			if (nodes[i] == null || !writable[i]) {
				continue;
			}
			nodes[i].setRotation(restRotationSlots[i]);
		}
	}

	private void captureInitialTails() {
		for (int joint = 0; joint < nodes.length - 1; joint++) {
			if (nodes[joint] == null) {
				continue;
			}
			worldTailRest(joint, tmp);
			toCenter(tmp);
			int o = (joint + 1) * 3;
			x[o] = tmp.x;
			x[o + 1] = tmp.y;
			x[o + 2] = tmp.z;
			xPrev[o] = tmp.x;
			xPrev[o + 1] = tmp.y;
			xPrev[o + 2] = tmp.z;
		}
	}

	private void updateJoint(int joint, float dt) {
		NodeModel bone = nodes[joint];
		if (bone.getParent() != null) {
			parentWorld.set(bone.getParent().computeGlobalTransform(matScratch));
		} else {
			parentWorld.identity();
		}
		int ro = joint * 4;
		restQuat.set(restRotation[ro], restRotation[ro + 1], restRotation[ro + 2], restRotation[ro + 3]);
		initialLocal.translationRotateScale(translationOf(bone), restQuat, scaleOf(bone));
		worldInitial.set(parentWorld).mul(initialLocal);

		tmpMatrix.set(bone.computeGlobalTransform(matScratch));
		head.set(tmpMatrix.m30(), tmpMatrix.m31(), tmpMatrix.m32());
		float worldLen = worldBoneLength(joint, tmpMatrix);
		if (worldLen < 1.0E-6F) {
			worldLen = DEFAULT_TIP_LENGTH;
		}

		int to = (joint + 1) * 3;
		float cx = x[to];
		float cy = x[to + 1];
		float cz = x[to + 2];
		float px = xPrev[to];
		float py = xPrev[to + 1];
		float pz = xPrev[to + 2];
		float damp = 1.0F - drag[joint];

		// Inertia in center space, then stiffness/gravity in world (three-vrm).
		nextTail.set(cx + (cx - px) * damp, cy + (cy - py) * damp, cz + (cz - pz) * damp);
		fromCenter(nextTail);

		int ao = joint * 3;
		tmp2.set(boneAxis[ao], boneAxis[ao + 1], boneAxis[ao + 2]);
		initialLocal.transformDirection(tmp2);
		parentWorld.transformDirection(tmp2);
		float stiffScale = stiffness[joint] * dt;
		nextTail.add(tmp2.x * stiffScale, tmp2.y * stiffScale, tmp2.z * stiffScale);

		float gScale = gravityPower[joint] * dt;
		nextTail.add(gravityDir[joint * 3] * gScale, gravityDir[joint * 3 + 1] * gScale,
			gravityDir[joint * 3 + 2] * gScale);

		constrainLength(nextTail, head, worldLen);
		collideTail(nextTail, hitRadius[joint], head, worldLen);

		tmpMatrix.set(worldInitial).invert();
		tmp.set(nextTail);
		tmpMatrix.transformPosition(tmp);
		if (tmp.lengthSquared() < 1.0E-12F) {
			return;
		}
		tmp.normalize();
		tmpQuat.rotationTo(boneAxis[ao], boneAxis[ao + 1], boneAxis[ao + 2], tmp.x, tmp.y, tmp.z);
		outQuat.set(restQuat).mul(tmpQuat);
		float[] write = writeRotationSlots[joint];
		write[0] = outQuat.x;
		write[1] = outQuat.y;
		write[2] = outQuat.z;
		write[3] = outQuat.w;
		bone.setRotation(write);

		xPrev[to] = cx;
		xPrev[to + 1] = cy;
		xPrev[to + 2] = cz;
		tmp.set(nextTail);
		toCenter(tmp);
		x[to] = tmp.x;
		x[to + 1] = tmp.y;
		x[to + 2] = tmp.z;
	}

	private float worldBoneLength(int joint, Matrix4f boneWorld) {
		int ao = joint * 3;
		tmp2.set(boneAxis[ao], boneAxis[ao + 1], boneAxis[ao + 2]).mul(boneLength[joint]);
		boneWorld.transformPosition(tmp2);
		return tmp2.distance(boneWorld.m30(), boneWorld.m31(), boneWorld.m32());
	}

	private void worldTailRest(int joint, Vector3f out) {
		tmpMatrix.set(nodes[joint].computeGlobalTransform(matScratch));
		int ao = joint * 3;
		out.set(boneAxis[ao], boneAxis[ao + 1], boneAxis[ao + 2]).mul(boneLength[joint]);
		tmpMatrix.transformPosition(out);
	}

	private void collideTail(Vector3f tail, float radius, Vector3f headPos, float worldLen) {
		if (colliders.isEmpty()) {
			return;
		}
		for (BoundCollider bound : colliders) {
			tmpMatrix.set(bound.node().computeGlobalTransform(matScratch));
			if (bound.collider().pushOut(tmpMatrix, tail.x, tail.y, tail.z, radius, tmp2, tmp)) {
				tail.set(tmp2);
				constrainLength(tail, headPos, worldLen);
			}
		}
	}

	private static void constrainLength(Vector3f tail, Vector3f headPos, float worldLen) {
		tail.sub(headPos);
		if (tail.lengthSquared() < 1.0E-12F) {
			tail.set(0.0F, worldLen, 0.0F);
		} else {
			tail.normalize().mul(worldLen);
		}
		tail.add(headPos);
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

	private Vector3f translationOf(NodeModel node) {
		float[] t = node.getTranslation();
		if (t != null && t.length >= 3) {
			return translationScratch.set(t[0], t[1], t[2]);
		}
		return translationScratch.set(0.0F, 0.0F, 0.0F);
	}

	private Vector3f scaleOf(NodeModel node) {
		float[] s = node.getScale();
		if (s != null && s.length >= 3) {
			return scaleScratch.set(s[0], s[1], s[2]);
		}
		return scaleScratch.set(1.0F, 1.0F, 1.0F);
	}

	record BoundCollider(NodeModel node, SpringBoneCollider collider) {
		BoundCollider {
			Objects.requireNonNull(node);
			Objects.requireNonNull(collider);
		}
	}

	static BoneClothGraph build(List<NodeModel> jointNodes, float[] stiffnessIn, float[] dragForce,
		float[] gravityPowerIn, float[][] gravityDirIn, float[] hitRadiusIn, List<BoundCollider> colliders,
		NodeModel center, Set<NodeModel> humanoidNodes) {
		int jointCount = jointNodes.size();
		if (jointCount < 1) {
			throw new IllegalArgumentException("spring needs at least one joint");
		}
		int particleCount = jointCount + 1;
		NodeModel[] nodes = new NodeModel[particleCount];
		boolean[] writable = new boolean[particleCount];
		float[] stiffness = new float[particleCount];
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
			float stiff = stiffnessIn[Math.min(i, stiffnessIn.length - 1)];
			float d = dragForce[Math.min(i, dragForce.length - 1)];
			float gp = gravityPowerIn[Math.min(i, gravityPowerIn.length - 1)];
			float[] gd = gravityDirIn[Math.min(i, gravityDirIn.length - 1)];
			float hr = hitRadiusIn[Math.min(i, hitRadiusIn.length - 1)];
			stiffness[i] = Math.max(0.0F, stiff);
			drag[i] = clamp01(d);
			gPower[i] = gp;
			gDir[i * 3] = gd[0];
			gDir[i * 3 + 1] = gd[1];
			gDir[i * 3 + 2] = gd[2];
			radii[i] = Math.max(0.0F, hr);
			captureRest(nodes[i], i, restRotation, boneAxis, boneLength, jointNodes, i == jointCount - 1);
		}
		int tip = particleCount - 1;
		nodes[tip] = null;
		writable[tip] = false;
		stiffness[tip] = stiffness[jointCount - 1];
		drag[tip] = drag[jointCount - 1];
		gPower[tip] = gPower[jointCount - 1];
		System.arraycopy(gDir, (jointCount - 1) * 3, gDir, tip * 3, 3);
		radii[tip] = radii[jointCount - 1];

		return new BoneClothGraph(nodes, writable, stiffness, drag, gPower, gDir, radii, restRotation, boneAxis,
			boneLength, colliders == null ? List.of() : new ArrayList<>(colliders), center);
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
			} else if (lastJoint) {
				// three-vrm / VRM0 tip: 7cm along the joint's own local position hint.
				float[] t = node.getTranslation();
				if (t != null && t.length >= 3 && t[0] * t[0] + t[1] * t[1] + t[2] * t[2] > 1.0E-8F) {
					axis.set(t[0], t[1], t[2]).normalize();
				}
				length = DEFAULT_TIP_LENGTH;
			}
		}
		int ao = index * 3;
		boneAxis[ao] = axis.x;
		boneAxis[ao + 1] = axis.y;
		boneAxis[ao + 2] = axis.z;
		boneLength[index] = length;
	}

	private static float clamp01(float v) {
		return Math.min(1.0F, Math.max(0.0F, v));
	}
}
