package io.github.westernbear.celerant.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.NodeModel;
import de.javagl.jgltf.model.impl.DefaultNodeModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

final class VrmRig {

	private static final List<String> REQUIRED_BONES = List.of(
		"hips", "spine", "head",
		"leftUpperLeg", "leftLowerLeg", "leftFoot",
		"rightUpperLeg", "rightLowerLeg", "rightFoot",
		"leftUpperArm", "leftLowerArm", "leftHand",
		"rightUpperArm", "rightLowerArm", "rightHand"
	);
	private static final float HALF_PI = (float) (Math.PI * 0.5D);

	private final Map<String, Bone> bones;
	private final String problem;

	private VrmRig(Map<String, Bone> bones, String problem) {
		this.bones = Map.copyOf(bones);
		this.problem = problem;
	}

	static VrmRig create(GltfModel model, Map<String, Integer> mapping) {
		List<NodeModel> nodes = model.getNodeModels();
		LinkedHashMap<String, Bone> bones = new LinkedHashMap<>();
		String invalid = null;
		for (Map.Entry<String, Integer> entry : mapping.entrySet()) {
			int index = entry.getValue();
			if (index < 0 || index >= nodes.size()) {
				invalid = "VRM humanoid bone " + entry.getKey() + " has an invalid node";
				break;
			}
			NodeModel node = nodes.get(index);
			Bone bone = Bone.capture(node);
			if (!bone.valid()) {
				invalid = "VRM humanoid bone " + entry.getKey() + " has an invalid rest transform";
				break;
			}
			bones.put(entry.getKey(), bone);
		}

		if (invalid == null) {
			List<String> missing = REQUIRED_BONES.stream().filter(name -> !bones.containsKey(name)).toList();
			if (!missing.isEmpty()) {
				invalid = "VRM humanoid is missing required bones: " + String.join(", ", missing);
			}
		}
		if (invalid == null) {
			invalid = validateHierarchy(bones);
		}
		if (invalid == null) {
			bones.values().forEach(Bone::activate);
		}
		return new VrmRig(bones, invalid);
	}

	boolean isUsable() {
		return problem == null;
	}

	int boneCount() {
		return bones.size();
	}

	String problem() {
		return problem == null ? "-" : problem;
	}

	void apply(PlayerModel source, AvatarRenderState state, float verticalSpeed, boolean airborne) {
		restore();

		applyChain(source.body, "spine", "chest", "upperChest");
		applyChain(source.head, "neck", "head");
		applyArm("leftUpperArm", source.leftArm, HALF_PI);
		applyArm("rightUpperArm", source.rightArm, -HALF_PI);
		applyPart("leftUpperLeg", source.leftLeg, 1.0F);
		applyPart("rightUpperLeg", source.rightLeg, 1.0F);
		if (airborne) {
			applyAirPose(verticalSpeed);
		}

		if (state.isCrouching) {
			Bone hips = bones.get("hips");
			float[] translation = hips.translation().clone();
			float[] offset = hips.crouchTranslation();
			translation[0] += offset[0];
			translation[1] += offset[1];
			translation[2] += offset[2];
			hips.node().setTranslation(translation);
		}
	}

	private void applyAirPose(float verticalSpeed) {
		float rise = clamp01(verticalSpeed / 0.42F);
		float fall = clamp01(-verticalSpeed / 0.50F);
		rotateCurrent("spine", new Quaternionf().rotationX(0.18F * rise - 0.12F * fall));
		rotateCurrent("leftUpperArm", new Quaternionf().rotationXYZ(
			-0.65F * rise - 0.10F * fall, 0.0F, -0.15F * rise - 0.95F * fall));
		rotateCurrent("rightUpperArm", new Quaternionf().rotationXYZ(
			-0.65F * rise - 0.10F * fall, 0.0F, 0.15F * rise + 0.95F * fall));
		rotateCurrent("leftUpperLeg", new Quaternionf().rotationX(-1.00F * rise + 0.22F * fall));
		rotateCurrent("rightUpperLeg", new Quaternionf().rotationX(0.25F * rise + 0.22F * fall));
		rotateCurrent("leftLowerLeg", new Quaternionf().rotationX(1.10F * rise - 0.42F * fall));
		rotateCurrent("rightLowerLeg", new Quaternionf().rotationX(-0.35F * rise - 0.42F * fall));
	}

	void restore() {
		for (Bone bone : bones.values()) {
			bone.restore();
		}
	}

	float[] rotation(String bone) {
		Bone value = bones.get(bone);
		return value == null ? null : value.lastRotation().clone();
	}

	private void applyChain(ModelPart source, String... names) {
		List<String> present = new ArrayList<>(names.length);
		for (String name : names) {
			if (bones.containsKey(name)) {
				present.add(name);
			}
		}
		float weight = 1.0F / present.size();
		for (String name : present) {
			applyPart(name, source, weight);
		}
	}

	private void applyPart(String name, ModelPart source, float weight) {
		applyRotation(name, new Quaternionf().rotationXYZ(
			source.xRot * weight, source.yRot * weight, source.zRot * weight));
	}

	private void applyArm(String name, ModelPart source, float armDown) {
		Quaternionf delta = new Quaternionf()
			.rotationXYZ(source.xRot, source.yRot, source.zRot)
			.rotateZ(armDown);
		applyRotation(name, delta);
	}

	private void applyRotation(String name, Quaternionf delta) {
		Bone bone = bones.get(name);
		if (bone == null) {
			return;
		}
		setNormalizedRotation(bone, delta);
	}

	private void rotateCurrent(String name, Quaternionf delta) {
		Bone bone = bones.get(name);
		if (bone == null) {
			return;
		}
		float[] current = bone.normalizedRotation();
		setNormalizedRotation(bone,
			new Quaternionf(current[0], current[1], current[2], current[3]).mul(delta));
	}

	private void setNormalizedRotation(Bone bone, Quaternionf delta) {
		delta.normalize();
		if (!finite(delta)) {
			return;
		}
		Quaternionf target = retarget(
			quaternion(bone.parentWorldRotation()), delta, quaternion(bone.rotation())).normalize();
		if (!finite(target)) {
			return;
		}
		float[] rotation = {target.x, target.y, target.z, target.w};
		bone.node().setRotation(rotation);
		bone.setLastRotation(rotation);
		bone.setNormalizedRotation(new float[] {delta.x, delta.y, delta.z, delta.w});
	}

	private static Quaternionf retarget(Quaternionf parentWorldRest, Quaternionf normalizedDelta,
			Quaternionf boneRest) {
		return new Quaternionf(parentWorldRest).conjugate()
			.mul(normalizedDelta).mul(parentWorldRest).mul(boneRest);
	}

	private static Quaternionf quaternion(float[] value) {
		return new Quaternionf(value[0], value[1], value[2], value[3]);
	}

	private static boolean finite(Quaternionf value) {
		return Float.isFinite(value.x) && Float.isFinite(value.y)
			&& Float.isFinite(value.z) && Float.isFinite(value.w);
	}

	static void selfCheck() {
		Quaternionf parent = new Quaternionf().rotationZ(0.61F);
		Quaternionf delta = new Quaternionf().rotationX(-0.43F);
		Quaternionf rest = new Quaternionf().rotationY(0.27F);
		Quaternionf local = retarget(parent, delta, rest);
		Quaternionf recovered = new Quaternionf(parent).mul(local)
			.mul(new Quaternionf(rest).conjugate()).mul(new Quaternionf(parent).conjugate()).normalize();
		if (Math.abs(recovered.dot(delta)) < 0.99999F) {
			throw new AssertionError("normalized humanoid retarget self-check failed");
		}

		DefaultNodeModel parentNode = new DefaultNodeModel();
		parentNode.setRotation(new float[] {parent.x, parent.y, parent.z, parent.w});
		parentNode.setScale(new float[] {2.0F, 2.0F, 2.0F});
		DefaultNodeModel boneNode = new DefaultNodeModel();
		Vector3f boneTranslation = new Vector3f(0.2F, 0.8F, -0.3F);
		boneNode.setMatrix(new Matrix4f().translationRotateScale(
			boneTranslation, rest, new Vector3f(1.0F, 1.0F, 1.0F)).get(new float[16]));
		parentNode.addChild(boneNode);
		Bone bone = Bone.capture(boneNode);
		if (!bone.valid()) {
			throw new AssertionError("matrix-backed humanoid transform self-check failed");
		}
		bone.activate();
		if (boneNode.getMatrix() != null) {
			throw new AssertionError("matrix-backed humanoid node was not converted to TRS");
		}
		Quaternionf capturedLocal = retarget(
			quaternion(bone.parentWorldRotation()), delta, quaternion(bone.rotation())).normalize();
		boneNode.setRotation(new float[] {capturedLocal.x, capturedLocal.y, capturedLocal.z, capturedLocal.w});
		Quaternionf actualWorld = new Matrix4f().set(boneNode.computeGlobalTransform(new float[16]))
			.getUnnormalizedRotation(new Quaternionf()).normalize();
		Quaternionf expectedWorld = new Quaternionf(delta).mul(parent).mul(rest).normalize();
		if (Math.abs(actualWorld.dot(expectedWorld)) < 0.9999F) {
			throw new AssertionError("normalized humanoid node transform self-check failed");
		}

		boneNode.setRotation(new float[] {rest.x, rest.y, rest.z, rest.w});
		float[] before = boneNode.computeGlobalTransform(new float[16]);
		float[] moved = bone.translation().clone();
		float[] crouch = bone.crouchTranslation();
		moved[0] += crouch[0];
		moved[1] += crouch[1];
		moved[2] += crouch[2];
		boneNode.setTranslation(moved);
		float[] after = boneNode.computeGlobalTransform(new float[16]);
		if (Math.abs(after[12] - before[12]) > 1.0E-5F
			|| Math.abs((after[13] - before[13]) + 0.18F) > 1.0E-5F
			|| Math.abs(after[14] - before[14]) > 1.0E-5F) {
			throw new AssertionError("normalized hips translation self-check failed");
		}
	}

	private static float clamp01(float value) {
		return Math.max(0.0F, Math.min(1.0F, value));
	}

	private static String validateHierarchy(Map<String, Bone> bones) {
		String[][] relations = {
			{"spine", "hips"}, {"head", "spine"},
			{"leftUpperLeg", "hips"}, {"leftLowerLeg", "leftUpperLeg"}, {"leftFoot", "leftLowerLeg"},
			{"rightUpperLeg", "hips"}, {"rightLowerLeg", "rightUpperLeg"}, {"rightFoot", "rightLowerLeg"},
			{"leftUpperArm", "spine"}, {"leftLowerArm", "leftUpperArm"}, {"leftHand", "leftLowerArm"},
			{"rightUpperArm", "spine"}, {"rightLowerArm", "rightUpperArm"}, {"rightHand", "rightLowerArm"}
		};
		for (String[] relation : relations) {
			if (!isDescendant(bones.get(relation[0]).node(), bones.get(relation[1]).node())) {
				return "VRM humanoid hierarchy is invalid at " + relation[0];
			}
		}
		return null;
	}

	private static boolean isDescendant(NodeModel child, NodeModel ancestor) {
		for (NodeModel current = child.getParent(); current != null; current = current.getParent()) {
			if (current == ancestor) {
				return true;
			}
		}
		return false;
	}

	private static final class Bone {
		private final NodeModel node;
		private final float[] translation;
		private final float[] rotation;
		private final float[] scale;
		private final float[] parentWorldRotation;
		private final float[] crouchTranslation;
		private final boolean matrixBacked;
		private final boolean valid;
		private float[] lastRotation;
		private float[] normalizedRotation = {0.0F, 0.0F, 0.0F, 1.0F};

		private Bone(NodeModel node, float[] translation, float[] rotation, float[] scale,
				float[] parentWorldRotation, float[] crouchTranslation, boolean matrixBacked, boolean valid) {
			this.node = node;
			this.translation = translation;
			this.rotation = rotation;
			this.scale = scale;
			this.parentWorldRotation = parentWorldRotation;
			this.crouchTranslation = crouchTranslation;
			this.matrixBacked = matrixBacked;
			this.valid = valid;
			this.lastRotation = rotation.clone();
		}

		static Bone capture(NodeModel node) {
			float[] matrix = node.getMatrix();
			boolean matrixBacked = matrix != null;
			boolean decomposable = true;
			float[] translation;
			float[] rotation;
			float[] scale;
			if (matrixBacked && matrix.length == 16 && finite(matrix)) {
				Matrix4f local = new Matrix4f().set(matrix);
				Vector3f localTranslation = local.getTranslation(new Vector3f());
				Quaternionf localRotation = local.getUnnormalizedRotation(new Quaternionf()).normalize();
				Vector3f localScale = local.getScale(new Vector3f());
				translation = new float[] {localTranslation.x, localTranslation.y, localTranslation.z};
				rotation = new float[] {localRotation.x, localRotation.y, localRotation.z, localRotation.w};
				scale = new float[] {localScale.x, localScale.y, localScale.z};
				float[] reconstructed = new Matrix4f().translationRotateScale(
					localTranslation, localRotation, localScale).get(new float[16]);
				decomposable = near(matrix, reconstructed, 1.0E-4F);
			} else {
				translation = copyOr(node.getTranslation(), new float[] {0.0F, 0.0F, 0.0F});
				rotation = copyOr(node.getRotation(), new float[] {0.0F, 0.0F, 0.0F, 1.0F});
				scale = copyOr(node.getScale(), new float[] {1.0F, 1.0F, 1.0F});
				decomposable = !matrixBacked;
			}
			Matrix4f parentTransform = new Matrix4f();
			if (node.getParent() != null) {
				float[] transform = node.getParent().computeGlobalTransform(new float[16]);
				parentTransform.set(transform);
			}
			Quaternionf parentRotation = parentTransform.getUnnormalizedRotation(new Quaternionf()).normalize();
			float[] parentWorldRotation = {parentRotation.x, parentRotation.y, parentRotation.z, parentRotation.w};
			Vector3f crouch = new Matrix4f(parentTransform).invert()
				.transformDirection(0.0F, -0.18F, 0.0F, new Vector3f());
			float[] crouchTranslation = {crouch.x, crouch.y, crouch.z};
			boolean valid = decomposable && translation.length == 3 && rotation.length == 4 && scale.length == 3
				&& finite(translation) && finite(rotation) && finite(scale)
				&& finite(parentWorldRotation) && finite(crouchTranslation)
				&& scale[0] > 0.0F && scale[1] > 0.0F && scale[2] > 0.0F
				&& rotation[0] * rotation[0] + rotation[1] * rotation[1]
					+ rotation[2] * rotation[2] + rotation[3] * rotation[3] > 1.0E-8F;
			if (valid) {
				Quaternionf normalized = new Quaternionf(rotation[0], rotation[1], rotation[2], rotation[3]).normalize();
				rotation = new float[] {normalized.x, normalized.y, normalized.z, normalized.w};
			}
			return new Bone(node, translation, rotation, scale, parentWorldRotation, crouchTranslation,
				matrixBacked, valid);
		}

		void activate() {
			if (matrixBacked) {
				node.setMatrix(null);
				node.setTranslation(translation.clone());
				node.setRotation(rotation.clone());
				node.setScale(scale.clone());
			}
		}

		void restore() {
			node.setTranslation(translation.clone());
			node.setRotation(rotation.clone());
			node.setScale(scale.clone());
			normalizedRotation = new float[] {0.0F, 0.0F, 0.0F, 1.0F};
		}

		NodeModel node() {
			return node;
		}

		float[] translation() {
			return translation;
		}

		float[] rotation() {
			return rotation;
		}

		float[] scale() {
			return scale;
		}

		float[] parentWorldRotation() {
			return parentWorldRotation;
		}

		float[] crouchTranslation() {
			return crouchTranslation;
		}

		boolean valid() {
			return valid;
		}

		float[] lastRotation() {
			return lastRotation;
		}

		void setLastRotation(float[] value) {
			lastRotation = value.clone();
		}

		float[] normalizedRotation() {
			return normalizedRotation;
		}

		void setNormalizedRotation(float[] value) {
			normalizedRotation = value.clone();
		}

		private static float[] copyOr(float[] value, float[] fallback) {
			return value == null ? fallback : value.clone();
		}

		private static boolean finite(float[] values) {
			for (float value : values) {
				if (!Float.isFinite(value)) {
					return false;
				}
			}
			return true;
		}

		private static boolean near(float[] first, float[] second, float epsilon) {
			for (int index = 0; index < first.length; index++) {
				if (Math.abs(first[index] - second[index]) > epsilon) {
					return false;
				}
			}
			return true;
		}
	}
}
