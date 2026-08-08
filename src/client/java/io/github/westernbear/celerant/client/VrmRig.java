package io.github.westernbear.celerant.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.NodeModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

import org.joml.Quaternionf;

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
			if (node.getMatrix() != null) {
				invalid = "VRM humanoid bone " + entry.getKey() + " uses a matrix transform";
				break;
			}
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
			translation[1] -= 0.18F;
			hips.node().setTranslation(translation);
		}
	}

	private void applyAirPose(float verticalSpeed) {
		float rise = clamp01(verticalSpeed / 0.42F);
		float fall = clamp01(-verticalSpeed / 0.50F);
		rotateCurrent("spine", new Quaternionf().rotationX(0.10F * rise - 0.08F * fall));
		rotateCurrent("leftUpperArm", new Quaternionf().rotationXYZ(
			-0.35F * rise - 0.20F * fall, 0.0F, -0.30F * fall));
		rotateCurrent("rightUpperArm", new Quaternionf().rotationXYZ(
			-0.35F * rise - 0.20F * fall, 0.0F, 0.30F * fall));
		rotateCurrent("leftUpperLeg", new Quaternionf().rotationX(-0.18F * rise + 0.12F * fall));
		rotateCurrent("rightUpperLeg", new Quaternionf().rotationX(0.18F * rise - 0.12F * fall));
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
		// ponytail: standard VRM local axes cover normal rigs; add a normalized
		// humanoid proxy only when a real model with non-standard bone roll needs it.
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
		float[] base = bone.rotation();
		setRotation(bone, new Quaternionf(base[0], base[1], base[2], base[3]).mul(delta));
	}

	private void rotateCurrent(String name, Quaternionf delta) {
		Bone bone = bones.get(name);
		if (bone == null) {
			return;
		}
		float[] current = bone.node().getRotation();
		setRotation(bone, new Quaternionf(current[0], current[1], current[2], current[3]).mul(delta));
	}

	private void setRotation(Bone bone, Quaternionf target) {
		target.normalize();
		if (!Float.isFinite(target.x) || !Float.isFinite(target.y)
			|| !Float.isFinite(target.z) || !Float.isFinite(target.w)) {
			return;
		}
		float[] rotation = {target.x, target.y, target.z, target.w};
		bone.node().setRotation(rotation);
		bone.setLastRotation(rotation);
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
		private final boolean valid;
		private float[] lastRotation;

		private Bone(NodeModel node, float[] translation, float[] rotation, float[] scale, boolean valid) {
			this.node = node;
			this.translation = translation;
			this.rotation = rotation;
			this.scale = scale;
			this.valid = valid;
			this.lastRotation = rotation.clone();
		}

		static Bone capture(NodeModel node) {
			float[] translation = copyOr(node.getTranslation(), new float[] {0.0F, 0.0F, 0.0F});
			float[] rotation = copyOr(node.getRotation(), new float[] {0.0F, 0.0F, 0.0F, 1.0F});
			float[] scale = copyOr(node.getScale(), new float[] {1.0F, 1.0F, 1.0F});
			boolean valid = translation.length == 3 && rotation.length == 4 && scale.length == 3
				&& finite(translation) && finite(rotation) && finite(scale)
				&& scale[0] > 0.0F && scale[1] > 0.0F && scale[2] > 0.0F
				&& rotation[0] * rotation[0] + rotation[1] * rotation[1]
					+ rotation[2] * rotation[2] + rotation[3] * rotation[3] > 1.0E-8F;
			if (valid) {
				Quaternionf normalized = new Quaternionf(rotation[0], rotation[1], rotation[2], rotation[3]).normalize();
				rotation = new float[] {normalized.x, normalized.y, normalized.z, normalized.w};
			}
			return new Bone(node, translation, rotation, scale, valid);
		}

		void restore() {
			node.setTranslation(translation.clone());
			node.setRotation(rotation.clone());
			node.setScale(scale.clone());
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

		boolean valid() {
			return valid;
		}

		float[] lastRotation() {
			return lastRotation;
		}

		void setLastRotation(float[] value) {
			lastRotation = value.clone();
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
	}
}
