package io.github.westernbear.celerant.client.physics;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.NodeModel;

/**
 * Builds {@link BoneClothGraph}s from VRMC_springBone or VRM0 secondaryAnimation.
 */
public final class VrmSpringBoneParser {
	private VrmSpringBoneParser() {
	}

	public static BoneClothSimulator parse(JsonObject root, GltfModel model, Map<String, Integer> humanoid) {
		Set<NodeModel> humanoidNodes = humanoidNodes(model, humanoid);
		JsonObject extensions = object(root, "extensions");
		if (extensions == null) {
			return BoneClothSimulator.empty();
		}
		JsonObject vrm1 = object(extensions, "VRMC_springBone");
		if (vrm1 != null) {
			return parseVrm1(vrm1, model, humanoidNodes);
		}
		JsonObject vrm0 = object(extensions, "VRM");
		if (vrm0 != null) {
			JsonObject secondary = object(vrm0, "secondaryAnimation");
			if (secondary != null) {
				return parseVrm0(secondary, model, humanoidNodes);
			}
		}
		return BoneClothSimulator.empty();
	}

	private static BoneClothSimulator parseVrm1(JsonObject springBone, GltfModel model, Set<NodeModel> humanoidNodes) {
		List<NodeModel> nodes = model.getNodeModels();
		List<BoneClothGraph.BoundCollider> allColliders = new ArrayList<>();
		JsonArray collidersJson = array(springBone, "colliders");
		if (collidersJson != null) {
			for (JsonElement element : collidersJson) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject collider = element.getAsJsonObject();
				int nodeIndex = integer(collider, "node", -1);
				if (nodeIndex < 0 || nodeIndex >= nodes.size()) {
					continue;
				}
				SpringBoneCollider shape = parseVrm1Shape(object(collider, "shape"));
				if (shape == null) {
					continue;
				}
				allColliders.add(new BoneClothGraph.BoundCollider(nodes.get(nodeIndex), shape));
			}
		}
		List<List<BoneClothGraph.BoundCollider>> groups = new ArrayList<>();
		JsonArray groupsJson = array(springBone, "colliderGroups");
		if (groupsJson != null) {
			for (JsonElement element : groupsJson) {
				List<BoneClothGraph.BoundCollider> group = new ArrayList<>();
				if (element.isJsonObject()) {
					JsonArray indices = array(element.getAsJsonObject(), "colliders");
					if (indices != null) {
						for (JsonElement indexElement : indices) {
							int index = indexElement.getAsInt();
							if (index >= 0 && index < allColliders.size()) {
								group.add(allColliders.get(index));
							}
						}
					}
				}
				groups.add(group);
			}
		}

		List<BoneClothGraph> graphs = new ArrayList<>();
		JsonArray springs = array(springBone, "springs");
		if (springs == null) {
			return BoneClothSimulator.empty();
		}
		for (JsonElement springElement : springs) {
			if (!springElement.isJsonObject()) {
				continue;
			}
			JsonObject spring = springElement.getAsJsonObject();
			JsonArray joints = array(spring, "joints");
			if (joints == null || joints.size() < 1) {
				continue;
			}
			List<NodeModel> jointNodes = new ArrayList<>();
			float[] stiffness = new float[joints.size()];
			float[] drag = new float[joints.size()];
			float[] gravityPower = new float[joints.size()];
			float[][] gravityDir = new float[joints.size()][3];
			float[] hitRadius = new float[joints.size()];
			boolean skip = false;
			for (int i = 0; i < joints.size(); i++) {
				JsonObject joint = joints.get(i).getAsJsonObject();
				int nodeIndex = integer(joint, "node", -1);
				if (nodeIndex < 0 || nodeIndex >= nodes.size()) {
					skip = true;
					break;
				}
				jointNodes.add(nodes.get(nodeIndex));
				stiffness[i] = number(joint, "stiffness", 1.0F);
				drag[i] = number(joint, "dragForce", 0.4F);
				gravityPower[i] = number(joint, "gravityPower", 0.0F);
				gravityDir[i] = vec3(joint, "gravityDir", 0.0F, -1.0F, 0.0F);
				hitRadius[i] = number(joint, "hitRadius", 0.0F);
			}
			if (skip || jointNodes.isEmpty()) {
				continue;
			}
			List<BoneClothGraph.BoundCollider> springColliders = new ArrayList<>();
			JsonArray groupIndices = array(spring, "colliderGroups");
			if (groupIndices != null) {
				for (JsonElement indexElement : groupIndices) {
					int index = indexElement.getAsInt();
					if (index >= 0 && index < groups.size()) {
						springColliders.addAll(groups.get(index));
					}
				}
			}
			NodeModel center = null;
			if (spring.has("center") && spring.get("center").isJsonPrimitive()) {
				int centerIndex = spring.get("center").getAsInt();
				if (centerIndex >= 0 && centerIndex < nodes.size()) {
					center = nodes.get(centerIndex);
				}
			}
			graphs.add(BoneClothGraph.build(jointNodes, stiffness, drag, gravityPower, gravityDir, hitRadius,
				springColliders, center, humanoidNodes));
		}
		return new BoneClothSimulator(graphs);
	}

	private static SpringBoneCollider parseVrm1Shape(JsonObject shape) {
		if (shape == null) {
			return null;
		}
		JsonObject sphere = object(shape, "sphere");
		if (sphere != null) {
			float[] offset = vec3(sphere, "offset", 0.0F, 0.0F, 0.0F);
			return SpringBoneCollider.sphere(offset[0], offset[1], offset[2], number(sphere, "radius", 0.0F));
		}
		JsonObject capsule = object(shape, "capsule");
		if (capsule != null) {
			float[] offset = vec3(capsule, "offset", 0.0F, 0.0F, 0.0F);
			float[] tail = vec3(capsule, "tail", 0.0F, 0.0F, 0.0F);
			return SpringBoneCollider.capsule(offset[0], offset[1], offset[2], tail[0], tail[1], tail[2],
				number(capsule, "radius", 0.0F));
		}
		return null;
	}

	private static BoneClothSimulator parseVrm0(JsonObject secondary, GltfModel model, Set<NodeModel> humanoidNodes) {
		List<NodeModel> nodes = model.getNodeModels();
		List<List<BoneClothGraph.BoundCollider>> groups = new ArrayList<>();
		JsonArray colliderGroups = array(secondary, "colliderGroups");
		if (colliderGroups != null) {
			for (JsonElement element : colliderGroups) {
				List<BoneClothGraph.BoundCollider> group = new ArrayList<>();
				if (!element.isJsonObject()) {
					groups.add(group);
					continue;
				}
				JsonObject groupJson = element.getAsJsonObject();
				int nodeIndex = integer(groupJson, "node", -1);
				if (nodeIndex < 0 || nodeIndex >= nodes.size()) {
					groups.add(group);
					continue;
				}
				NodeModel node = nodes.get(nodeIndex);
				JsonArray colliders = array(groupJson, "colliders");
				if (colliders != null) {
					for (JsonElement colliderElement : colliders) {
						if (!colliderElement.isJsonObject()) {
							continue;
						}
						JsonObject collider = colliderElement.getAsJsonObject();
						float[] offset = vec3Object(object(collider, "offset"), 0.0F, 0.0F, 0.0F);
						group.add(new BoneClothGraph.BoundCollider(node,
							SpringBoneCollider.sphere(offset[0], offset[1], offset[2],
								number(collider, "radius", 0.0F))));
					}
				}
				groups.add(group);
			}
		}

		List<BoneClothGraph> graphs = new ArrayList<>();
		JsonArray boneGroups = array(secondary, "boneGroups");
		if (boneGroups == null) {
			return BoneClothSimulator.empty();
		}
		for (JsonElement element : boneGroups) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject group = element.getAsJsonObject();
			JsonArray bones = array(group, "bones");
			if (bones == null || bones.size() < 1) {
				continue;
			}
			List<NodeModel> jointNodes = new ArrayList<>();
			boolean skip = false;
			for (JsonElement boneElement : bones) {
				int index = boneElement.getAsInt();
				if (index < 0 || index >= nodes.size()) {
					skip = true;
					break;
				}
				jointNodes.add(nodes.get(index));
			}
			if (skip || jointNodes.isEmpty()) {
				continue;
			}
			float stiff = number(group, "stiffiness", number(group, "stiffness", 1.0F));
			float drag = number(group, "dragForce", 0.4F);
			float gp = number(group, "gravityPower", 0.0F);
			float[] gd = vec3Object(object(group, "gravityDir"), 0.0F, -1.0F, 0.0F);
			float hr = number(group, "hitRadius", 0.02F);
			int n = jointNodes.size();
			float[] stiffness = fill(n, stiff);
			float[] dragArr = fill(n, drag);
			float[] gravityPower = fill(n, gp);
			float[][] gravityDir = new float[n][];
			for (int i = 0; i < n; i++) {
				gravityDir[i] = gd.clone();
			}
			float[] hitRadius = fill(n, hr);
			List<BoneClothGraph.BoundCollider> springColliders = new ArrayList<>();
			JsonArray groupIndices = array(group, "colliderGroups");
			if (groupIndices != null) {
				for (JsonElement indexElement : groupIndices) {
					int index = indexElement.getAsInt();
					if (index >= 0 && index < groups.size()) {
						springColliders.addAll(groups.get(index));
					}
				}
			}
			NodeModel center = null;
			int centerIndex = integer(group, "center", -1);
			if (centerIndex >= 0 && centerIndex < nodes.size()) {
				center = nodes.get(centerIndex);
			}
			graphs.add(BoneClothGraph.build(jointNodes, stiffness, dragArr, gravityPower, gravityDir, hitRadius,
				springColliders, center, humanoidNodes));
		}
		return new BoneClothSimulator(graphs);
	}

	private static Set<NodeModel> humanoidNodes(GltfModel model, Map<String, Integer> humanoid) {
		IdentityHashMap<NodeModel, Boolean> set = new IdentityHashMap<>();
		List<NodeModel> nodes = model.getNodeModels();
		for (Integer index : humanoid.values()) {
			if (index != null && index >= 0 && index < nodes.size()) {
				set.put(nodes.get(index), Boolean.TRUE);
			}
		}
		return set.keySet();
	}

	private static float[] fill(int n, float value) {
		float[] out = new float[n];
		java.util.Arrays.fill(out, value);
		return out;
	}

	private static JsonObject object(JsonObject parent, String key) {
		if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) {
			return null;
		}
		return parent.getAsJsonObject(key);
	}

	private static JsonArray array(JsonObject parent, String key) {
		if (parent == null || !parent.has(key) || !parent.get(key).isJsonArray()) {
			return null;
		}
		return parent.getAsJsonArray(key);
	}

	private static int integer(JsonObject parent, String key, int fallback) {
		if (parent == null || !parent.has(key) || !parent.get(key).isJsonPrimitive()) {
			return fallback;
		}
		try {
			return parent.get(key).getAsInt();
		} catch (RuntimeException ignored) {
			return fallback;
		}
	}

	private static float number(JsonObject parent, String key, float fallback) {
		if (parent == null || !parent.has(key) || !parent.get(key).isJsonPrimitive()) {
			return fallback;
		}
		try {
			return parent.get(key).getAsFloat();
		} catch (RuntimeException ignored) {
			return fallback;
		}
	}

	private static float[] vec3(JsonObject parent, String key, float x, float y, float z) {
		if (parent == null || !parent.has(key)) {
			return new float[] {x, y, z};
		}
		JsonElement element = parent.get(key);
		if (element.isJsonArray()) {
			JsonArray array = element.getAsJsonArray();
			return new float[] {
				array.size() > 0 ? array.get(0).getAsFloat() : x,
				array.size() > 1 ? array.get(1).getAsFloat() : y,
				array.size() > 2 ? array.get(2).getAsFloat() : z
			};
		}
		if (element.isJsonObject()) {
			return vec3Object(element.getAsJsonObject(), x, y, z);
		}
		return new float[] {x, y, z};
	}

	private static float[] vec3Object(JsonObject vec, float x, float y, float z) {
		if (vec == null) {
			return new float[] {x, y, z};
		}
		return new float[] {number(vec, "x", x), number(vec, "y", y), number(vec, "z", z)};
	}
}
