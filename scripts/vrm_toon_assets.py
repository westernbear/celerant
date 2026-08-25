#!/usr/bin/env python3
"""Derive Genshin-style ToonShader sidecar data for any VRM.

VRM models carry no illumination map, shadow ramp or facial SDF, which is the data
the Genshin shading model is built around. This tool reconstructs that data from
whatever the model does provide: it rasterises each material into UV space, derives
the illumination map channels from albedo and geometry, builds the facial SDF from
the head's own azimuth, and writes a ramp sheet in the official layout. Everything
here is derived per model at run time; nothing is keyed to a particular character.
"""

from __future__ import annotations

import argparse
import io
import json
import struct
from pathlib import Path

import numpy as np
from PIL import Image


def load_glb(path: Path) -> tuple[dict, bytes]:
	data = path.read_bytes()
	magic, version, length = struct.unpack_from("<4sII", data)
	if magic != b"glTF" or version != 2 or length != len(data):
		raise ValueError(f"{path.name} is not a valid glTF 2 GLB")
	offset = 12
	document = None
	binary = None
	while offset < len(data):
		chunk_length, chunk_type = struct.unpack_from("<II", data, offset)
		offset += 8
		chunk = data[offset : offset + chunk_length]
		offset += chunk_length
		if chunk_type == 0x4E4F534A:
			document = json.loads(chunk.rstrip(b" \0"))
		elif chunk_type == 0x004E4942:
			binary = chunk
	if document is None or binary is None:
		raise ValueError(f"{path.name} is missing JSON or BIN data")
	return document, binary


def accessor(document: dict, binary: bytes, index: int) -> np.ndarray:
	value = document["accessors"][index]
	view = document["bufferViews"][value["bufferView"]]
	component = {
		5120: np.dtype("i1"),
		5121: np.dtype("u1"),
		5122: np.dtype("<i2"),
		5123: np.dtype("<u2"),
		5125: np.dtype("<u4"),
		5126: np.dtype("<f4"),
	}[value["componentType"]]
	components = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[value["type"]]
	item_size = component.itemsize * components
	stride = view.get("byteStride", item_size)
	offset = view.get("byteOffset", 0) + value.get("byteOffset", 0)
	result = np.ndarray(
		(value["count"], components),
		dtype=component,
		buffer=binary,
		offset=offset,
		strides=(stride, component.itemsize),
	).copy()
	if value.get("normalized"):
		if np.issubdtype(component, np.signedinteger):
			result = np.maximum(result / np.iinfo(component).max, -1.0)
		elif np.issubdtype(component, np.unsignedinteger):
			result = result / np.iinfo(component).max
	return result


def triangles(indices: np.ndarray, mode: int) -> list[tuple[int, int, int]]:
	values = indices.reshape(-1).tolist()
	if mode == 4:
		return [tuple(values[i : i + 3]) for i in range(0, len(values) - 2, 3)]
	if mode == 5:
		return [
			(values[i + 1], values[i], values[i + 2]) if i & 1 else (values[i], values[i + 1], values[i + 2])
			for i in range(len(values) - 2)
		]
	if mode == 6:
		return [(values[0], values[i], values[i + 1]) for i in range(1, len(values) - 1)]
	raise ValueError(f"Unsupported primitive mode {mode}")


def embedded_base_image(document: dict, binary: bytes, material_index: int) -> Image.Image:
	material = document["materials"][material_index]
	pbr = material.get("pbrMetallicRoughness", {})
	if "baseColorTexture" not in pbr:
		factor = pbr.get("baseColorFactor", [1.0, 1.0, 1.0, 1.0])
		return Image.new("RGBA", (1, 1), tuple(round(channel * 255) for channel in factor))
	texture_index = pbr["baseColorTexture"]["index"]
	image_index = document["textures"][texture_index]["source"]
	image = document["images"][image_index]
	view = document["bufferViews"][image["bufferView"]]
	start = view.get("byteOffset", 0)
	payload = binary[start : start + view["byteLength"]]
	return Image.open(io.BytesIO(payload)).convert("RGBA")


def rasterized_material(
	document: dict,
	binary: bytes,
	size: tuple[int, int],
	material_index: int,
	allow_empty: bool = False,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
	width, height = size
	# Where several triangles share a texel, keep the one facing the way the character
	# does. Preferring -Z outright would rasterise the back of a VRM 1.0 model, whose
	# characters face +Z.
	forward_sign = head_forward(document)[2]
	best = np.full((height, width), -np.inf, dtype=np.float32)
	positions = np.zeros((height, width, 3), dtype=np.float32)
	normals = np.zeros((height, width, 3), dtype=np.float32)
	head_weights = np.zeros((height, width), dtype=np.float32)
	head_node = head_node_index(document)
	head_nodes = head_subtree(document, head_node) if head_node is not None else set()
	skins = mesh_skins(document)
	for mesh_index, mesh in enumerate(document["meshes"]):
		head_joints: set[int] = set()
		if mesh_index in skins:
			joints = document["skins"][skins[mesh_index]].get("joints", [])
			head_joints = {slot for slot, node in enumerate(joints) if node in head_nodes}
		for primitive in mesh["primitives"]:
			attributes = primitive["attributes"]
			if (
				primitive.get("material") != material_index
				or "TEXCOORD_0" not in attributes
				or "POSITION" not in attributes
				or "NORMAL" not in attributes
			):
				continue
			uv = accessor(document, binary, attributes["TEXCOORD_0"]).astype(np.float32)
			source_positions = accessor(document, binary, attributes["POSITION"]).astype(np.float32)
			source_normals = accessor(document, binary, attributes["NORMAL"]).astype(np.float32)
			if "indices" in primitive:
				index_values = accessor(document, binary, primitive["indices"])
			else:
				index_values = np.arange(len(uv), dtype=np.uint32)[:, None]
			vertex_head = np.zeros(len(uv), dtype=np.float32)
			if head_joints and "JOINTS_0" in attributes and "WEIGHTS_0" in attributes:
				slots = accessor(document, binary, attributes["JOINTS_0"]).astype(np.int64)
				bone_weights = accessor(document, binary, attributes["WEIGHTS_0"]).astype(np.float32)
				vertex_head = (bone_weights * np.isin(slots, list(head_joints))).sum(axis=1)
			for a, b, c in triangles(index_values, primitive.get("mode", 4)):
				points = uv[[a, b, c]] * np.array([width - 1, height - 1], dtype=np.float32)
				minimum = np.maximum(np.floor(points.min(axis=0)).astype(int), 0)
				maximum = np.minimum(np.ceil(points.max(axis=0)).astype(int), [width - 1, height - 1])
				if np.any(maximum < minimum):
					continue
				x0, y0 = points[0]
				x1, y1 = points[1]
				x2, y2 = points[2]
				denominator = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
				if abs(denominator) < 1.0e-8:
					continue
				grid_x, grid_y = np.meshgrid(
					np.arange(minimum[0], maximum[0] + 1, dtype=np.float32) + 0.5,
					np.arange(minimum[1], maximum[1] + 1, dtype=np.float32) + 0.5,
				)
				weight_a = ((y1 - y2) * (grid_x - x2) + (x2 - x1) * (grid_y - y2)) / denominator
				weight_b = ((y2 - y0) * (grid_x - x2) + (x0 - x2) * (grid_y - y2)) / denominator
				weight_c = 1.0 - weight_a - weight_b
				inside = (weight_a >= -1.0e-4) & (weight_b >= -1.0e-4) & (weight_c >= -1.0e-4)
				if not inside.any():
					continue
				weights = np.stack((weight_a, weight_b, weight_c), axis=-1)
				triangle_normals = source_normals[[a, b, c]]
				interpolated_normals = weights @ triangle_normals
				lengths = np.linalg.norm(interpolated_normals, axis=-1, keepdims=True)
				interpolated_normals /= np.maximum(lengths, 1.0e-8)
				score = interpolated_normals[..., 2] * forward_sign
				rows = slice(minimum[1], maximum[1] + 1)
				columns = slice(minimum[0], maximum[0] + 1)
				update = inside & (score > best[rows, columns])
				if not update.any():
					continue
				best[rows, columns][update] = score[update]
				interpolated_positions = weights @ source_positions[[a, b, c]]
				positions[rows, columns][update] = interpolated_positions[update]
				normals[rows, columns][update] = interpolated_normals[update]
				head_weights[rows, columns][update] = (weights @ vertex_head[[a, b, c]])[update]
	coverage = np.isfinite(best)
	if not coverage.any() and not allow_empty:
		raise ValueError(f"material {material_index} has no rasterizable UV triangles")
	return coverage, positions, normals, head_weights


def gaussian(x: np.ndarray, y: np.ndarray, cx: float, cy: float, rx: float, ry: float) -> np.ndarray:
	return np.exp(-0.5 * (((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2))


def weighted_center(mask: np.ndarray, x: np.ndarray, y: np.ndarray, weights: np.ndarray) -> tuple[float, float] | None:
	"""Centre of mass for a boolean mask in face-normalised coordinates."""
	selected = mask & (weights > 0.0)
	if not selected.any():
		return None
	weight = weights[selected]
	total = float(weight.sum())
	if total <= 0.0:
		return None
	return (
		float(np.average(x[selected], weights=weight)),
		float(np.average(y[selected], weights=weight)),
	)


def face_feature_centers(face: np.ndarray, centered_x: np.ndarray, y: np.ndarray,
	luminance: np.ndarray, saturation: np.ndarray) -> tuple[
		tuple[float, float], tuple[float, float], tuple[float, float], tuple[float, float]]:
	"""Place blush and eye masks from authored albedo on the face atlas, not constants.

	Eyes are the dark, saturated islands in the upper face. Cheeks are the neutral-skin
	lower-outer regions once the face bounds come from geometry rather than the whole
	texture.
	"""
	eye_candidates = face & (y > 0.35) & (y < 0.65) & (luminance < 0.72) & (saturation > 0.08)
	eye_weight = np.maximum(0.01, 0.8 - luminance + saturation)
	eye_left = weighted_center(eye_candidates & (centered_x < 0.0), centered_x, y, eye_weight)
	eye_right = weighted_center(eye_candidates & (centered_x > 0.0), centered_x, y, eye_weight)
	if eye_left is None:
		eye_left = (-0.28, 0.52)
	if eye_right is None:
		eye_right = (0.28, 0.52)

	cheek_candidates = face & (np.abs(centered_x) > 0.18) & (y > 0.22) & (y < 0.48)
	cheek_candidates &= (saturation < 0.28) & (luminance > 0.42)
	cheek_weight = np.abs(centered_x) * np.clip(0.45 - np.abs(y - 0.33), 0.05, 0.45)
	blush_left = weighted_center(cheek_candidates & (centered_x < 0.0), centered_x, y, cheek_weight)
	blush_right = weighted_center(cheek_candidates & (centered_x > 0.0), centered_x, y, cheek_weight)
	if blush_left is None:
		blush_left = (-0.38, 0.34)
	if blush_right is None:
		blush_right = (0.38, 0.34)
	return eye_left, eye_right, blush_left, blush_right


LIGHT_PARAMETERS = {
	# Genshin ILM convention: red is specular intensity and doubles as the metal
	# selector above 0.9, green is the shadow threshold where 0.5 means "let Lambert
	# decide" and 0.9 or above means "always lit", blue is the highlight threshold,
	# alpha selects the ramp row.
	# (ao_min, ao_max, specular_min, specular_max, blue, selector)
	"skin": (0.42, 0.56, 0.02, 0.08, 0.20, 0.30),
	"cloth": (0.40, 0.56, 0.06, 0.18, 0.35, 0.70),
	"matte": (0.40, 0.54, 0.01, 0.06, 0.15, 0.10),
	"hair": (0.40, 0.55, 0.18, 0.42, 0.55, 0.50),
	"metal": (0.42, 0.56, 0.94, 1.00, 0.90, 0.95),
	"eye": (1.00, 1.00, 0.30, 0.60, 0.45, 0.30),
	"face": (0.42, 0.56, 0.02, 0.08, 0.20, 0.30),
}


def outline_tint(document: dict, binary: bytes, material_index: int) -> list[float]:
	"""Genshin outlines are darkened, slightly saturated versions of the material's
	own colour rather than a single flat black."""
	image = np.asarray(embedded_base_image(document, binary, material_index), dtype=np.float32)
	rgb = image[..., :3] / 255.0
	weight = image[..., 3:4] / 255.0
	total = float(weight.sum())
	mean = (rgb * weight).reshape(-1, 3).sum(axis=0) / max(total, 1.0e-6)
	grey = float(mean @ np.array([0.2126, 0.7152, 0.0722], dtype=np.float32))
	saturated = np.clip(grey + (mean - grey) * 1.35, 0.0, 1.0)
	return [round(float(c), 4) for c in np.clip(saturated * 0.34 + 0.03, 0.0, 1.0)] + [1.0]


# Expression presets the VRM specification defines on the face. Whichever mesh these
# morph targets drive is the face mesh, which holds regardless of naming or language.
FACE_EXPRESSIONS_VRM0 = {"a", "i", "u", "e", "o", "blink", "blink_l", "blink_r",
	"joy", "angry", "sorrow", "fun"}
FACE_EXPRESSIONS_VRM1 = {"aa", "ih", "ou", "ee", "oh", "blink", "blinkLeft",
	"blinkRight", "happy", "angry", "sad", "relaxed"}


def expression_meshes(document: dict) -> set[int]:
	meshes: set[int] = set()
	extensions = document.get("extensions", {})
	groups = extensions.get("VRM", {}).get("blendShapeMaster", {}).get("blendShapeGroups", [])
	for group in groups:
		if group.get("presetName") in FACE_EXPRESSIONS_VRM0:
			for bind in group.get("binds", []):
				if isinstance(bind.get("mesh"), int):
					meshes.add(bind["mesh"])
	nodes = document.get("nodes", [])
	presets = extensions.get("VRMC_vrm", {}).get("expressions", {}).get("preset", {})
	for name, expression in presets.items():
		if name not in FACE_EXPRESSIONS_VRM1:
			continue
		for bind in expression.get("morphTargetBinds", []):
			node = bind.get("node")
			if isinstance(node, int) and node < len(nodes) and "mesh" in nodes[node]:
				meshes.add(nodes[node]["mesh"])
	return meshes


def head_node_index(document: dict) -> int | None:
	declared = declared_head_node(document)
	if declared is None:
		return None
	nodes = document.get("nodes", [])
	if nodes[declared].get("children"):
		return declared
	# Some exporters fill the humanoid map by joint index and land on a leaf such as an
	# eye. A head carries the eye, hair and jaw joints beneath it, so when the declared
	# joint is a leaf hanging off a joint that clearly is such a hub, take the hub.
	parent = parent_map(document).get(declared)
	if parent is not None and len(nodes[parent].get("children", [])) >= 3:
		return parent
	return declared


def declared_head_node(document: dict) -> int | None:
	extensions = document.get("extensions", {})
	bones = extensions.get("VRMC_vrm", {}).get("humanoid", {}).get("humanBones", {})
	if isinstance(bones, dict) and isinstance(bones.get("head"), dict):
		return bones["head"].get("node")
	for bone in extensions.get("VRM", {}).get("humanoid", {}).get("humanBones", []):
		if bone.get("bone") == "head":
			return bone.get("node")
	return None


def parent_map(document: dict) -> dict[int, int]:
	parents: dict[int, int] = {}
	for index, node in enumerate(document.get("nodes", [])):
		for child in node.get("children", []):
			parents[child] = index
	return parents


def head_subtree(document: dict, root: int) -> set[int]:
	nodes = document.get("nodes", [])
	found = {root}
	stack = [root]
	while stack:
		for child in nodes[stack.pop()].get("children", []):
			if child not in found:
				found.add(child)
				stack.append(child)
	return found


def node_origin(document: dict, index: int) -> np.ndarray:
	"""Rest-pose position of a node, accumulated up the hierarchy. VRM rest poses are
	axis aligned, so the translations alone locate the head well enough to tell the
	front of the head from the back."""
	parents = parent_map(document)
	total = np.zeros(3, dtype=np.float32)
	current: int | None = index
	visited: set[int] = set()
	while current is not None and current not in visited:
		visited.add(current)
		node = document["nodes"][current]
		if "translation" in node:
			total += np.asarray(node["translation"], dtype=np.float32)
		elif "matrix" in node:
			total += np.asarray(node["matrix"], dtype=np.float32)[12:15]
		current = parents.get(current)
	return total


def mesh_skins(document: dict) -> dict[int, int]:
	skins: dict[int, int] = {}
	for node in document.get("nodes", []):
		if "mesh" in node and "skin" in node:
			skins.setdefault(node["mesh"], node["skin"])
	return skins


def material_geometry(document: dict, binary: bytes) -> dict[int, dict]:
	"""Area, head attachment and orientation per material.

	These are the signals that exist in every VRM regardless of how the author named
	things, which is what the classification below runs on.
	"""
	head = head_node_index(document)
	head_nodes = head_subtree(document, head) if head is not None else set()
	origin = node_origin(document, head) if head is not None else np.zeros(3, dtype=np.float32)
	forward = np.asarray(head_forward(document), dtype=np.float32)
	up = np.asarray([0.0, 1.0, 0.0], dtype=np.float32)
	skins = mesh_skins(document)
	stats: dict[int, dict] = {}
	for mesh_index, mesh in enumerate(document.get("meshes", [])):
		head_joints: set[int] = set()
		if mesh_index in skins:
			joints = document["skins"][skins[mesh_index]].get("joints", [])
			head_joints = {slot for slot, node in enumerate(joints) if node in head_nodes}
		for primitive in mesh["primitives"]:
			material_index = primitive.get("material")
			attributes = primitive.get("attributes", {})
			if material_index is None or "POSITION" not in attributes:
				continue
			positions = accessor(document, binary, attributes["POSITION"]).astype(np.float32)
			if "indices" in primitive:
				index_values = accessor(document, binary, primitive["indices"])
			else:
				index_values = np.arange(len(positions), dtype=np.uint32)[:, None]
			faces = np.asarray(triangles(index_values, primitive.get("mode", 4)), dtype=np.int64)
			if faces.size == 0:
				continue
			vertex_head = np.zeros(len(positions), dtype=np.float32)
			if head_joints and "JOINTS_0" in attributes and "WEIGHTS_0" in attributes:
				slots = accessor(document, binary, attributes["JOINTS_0"]).astype(np.int64)
				weights = accessor(document, binary, attributes["WEIGHTS_0"]).astype(np.float32)
				mask = np.isin(slots, list(head_joints))
				vertex_head = (weights * mask).sum(axis=1)
			corners = positions[faces]
			cross = np.cross(corners[:, 1] - corners[:, 0], corners[:, 2] - corners[:, 0])
			areas = 0.5 * np.linalg.norm(cross, axis=1)
			lengths = np.maximum(np.linalg.norm(cross, axis=1, keepdims=True), 1.0e-12)
			face_normals = cross / lengths
			centroids = corners.mean(axis=1) - origin
			entry = stats.setdefault(material_index, {
				"area": 0.0, "forward_area": 0.0, "head_area": 0.0,
				"forward_offset": 0.0, "up_offset": 0.0, "meshes": set(),
			})
			entry["meshes"].add(mesh_index)
			entry["area"] += float(areas.sum())
			entry["forward_area"] += float((areas * np.maximum(face_normals @ forward, 0.0)).sum())
			entry["head_area"] += float((areas * vertex_head[faces].mean(axis=1)).sum())
			entry["forward_offset"] += float((areas * (centroids @ forward)).sum())
			entry["up_offset"] += float((areas * (centroids @ up)).sum())
	return stats


# Names are only a refinement: many VRMs use non-English or arbitrary material names,
# so tokens are matched whole to avoid accidents such as "Brown" matching "brow".
CATEGORY_TOKENS = {
	"eye": {"EYE", "EYES", "EYEWHITE", "EYEIRIS", "EYEHIGHLIGHT", "EYEEXTRA", "EYELINE",
		"EYELASH", "IRIS", "SCLERA", "CORNEA", "PUPIL", "눈", "目", "瞳"},
	"face": {"FACE", "BROW", "EYEBROW", "FACEBROW", "MOUTH", "TOOTH", "TEETH", "TONGUE",
		"LIP", "얼굴", "顔", "眉", "口"},
	"hair": {"HAIR", "AHOGE", "PONYTAIL", "BANGS", "머리", "머리카락", "헤어", "髪"},
	"metal": {"METAL", "METALLIC", "ACCESSORY", "SHOE", "SHOES", "BOOT", "BOOTS",
		"BUCKLE", "BUTTON", "JEWEL", "CHAIN", "ARMOR", "BADGE", "PEARL", "금속", "신발"},
	"skin": {"SKIN", "BODY", "몸", "바디", "피부", "肌", "体"},
	"cloth": {"CLOTH", "CLOTHES", "TOPS", "BOTTOMS", "ONEPIECE", "SKIRT", "SHIRT", "COAT",
		"DRESS", "SOCK", "SOCKS", "GLOVE", "GLOVES", "RIBBON", "옷", "服"},
}


def name_tokens(name: str) -> set[str]:
	tokens: set[str] = set()
	current = ""
	for character in name.upper():
		if character.isalnum():
			current += character
		else:
			if current:
				tokens.add(current)
			current = ""
	if current:
		tokens.add(current)
	return tokens


def name_category(name: str) -> str | None:
	tokens = name_tokens(name)
	for category in ("eye", "face", "hair", "metal", "skin", "cloth"):
		if tokens & CATEGORY_TOKENS[category]:
			return category
	return None


def classify_materials(document: dict, binary: bytes) -> tuple[dict[int, str], int | None]:
	"""Assign a shading category to every material and pick the face.

	The face is found from the VRM expression bindings first: the mesh the blink and
	vowel morphs drive is the face, and within it the face material is the one showing
	the most surface towards the front. Where a model carries no expressions this falls
	back to the head attachment the specification's own first-person "auto" rule uses.
	Remaining large head surfaces are hair, small ones are the facial features Genshin
	keeps permanently lit, and names only refine the result off the head, where
	geometry cannot tell a shoe buckle from a shirt.
	"""
	geometry = material_geometry(document, binary)
	materials = document.get("materials", [])
	categories: dict[int, str] = {}
	head_materials = {
		index: entry for index, entry in geometry.items()
		if entry["area"] > 0.0 and entry["head_area"] / entry["area"] > 0.5
	}
	face_meshes = expression_meshes(document)
	candidates = {
		index: entry for index, entry in geometry.items()
		if entry["meshes"] & face_meshes and entry["forward_area"] > 0.0
	}
	if not candidates:
		candidates = {
			index: entry for index, entry in head_materials.items()
			if entry["forward_offset"] > 0.0
		}
	face_index: int | None = None
	if candidates:
		face_index = max(candidates, key=lambda index: candidates[index]["forward_area"])
		head_materials.setdefault(face_index, geometry[face_index])
	face_area = head_materials[face_index]["area"] if face_index is not None else 0.0
	for index in range(len(materials)):
		name = materials[index].get("name", "")
		hinted = name_category(name)
		if index == face_index:
			categories[index] = "face"
			continue
		if index in head_materials:
			entry = head_materials[index]
			if hinted in {"eye", "face"}:
				categories[index] = hinted
			elif hinted in {"hair", "metal"}:
				categories[index] = hinted
			else:
				# Large remaining head surfaces are hair or headwear; the small ones are
				# brows, lashes and eyes, which stay lit rather than self-shadowing.
				categories[index] = "hair" if entry["area"] > 0.15 * face_area else "eye"
			continue
		categories[index] = hinted if hinted in {"metal", "skin", "cloth", "hair"} else "cloth"
	return categories, face_index


def write_light_map(document: dict, binary: bytes, material_index: int, category: str,
	root: Path, prefix: str) -> str:
	size = (512, 512)
	coverage, _, normals, _ = rasterized_material(
		document, binary, size, material_index, allow_empty=True
	)
	base = embedded_base_image(document, binary, material_index).resize(size, Image.Resampling.LANCZOS)
	rgb = np.asarray(base, dtype=np.float32)[..., :3] / 255.0
	luminance = rgb @ np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)
	parameters = np.empty((size[1], size[0], 6), dtype=np.float32)
	parameters[...] = LIGHT_PARAMETERS[category]
	ao_minimum, ao_maximum, specular_minimum, specular_maximum, blue, selector = np.moveaxis(
		parameters, -1, 0
	)
	detail = np.clip(0.55 + 0.45 * luminance, 0.0, 1.0)
	orientation = np.clip(0.55 + 0.45 * np.abs(normals[..., 1]), 0.0, 1.0)
	ao = ao_minimum + (ao_maximum - ao_minimum) * detail
	specular = specular_minimum + (specular_maximum - specular_minimum) * detail * orientation
	specular_blue = blue * (0.70 + 0.30 * detail)
	image = np.empty((size[1], size[0], 4), dtype=np.float32)
	image[..., 0] = np.where(coverage, specular, specular_minimum)
	image[..., 1] = np.where(coverage, ao, 1.0)
	image[..., 2] = np.where(coverage, specular_blue, blue)
	image[..., 3] = selector
	encoded = np.rint(np.clip(image, 0.0, 1.0) * 255.0).astype(np.uint8)
	name = f"{prefix}-light-{material_index}.png"
	Image.fromarray(encoded, "RGBA").save(root / name, optimize=True)
	return name


def write_face_assets(document: dict, binary: bytes, material_index: int,
	root: Path, prefix: str) -> dict:
	"""Procedural face LightMap / shadow SDF (no third-party textures)."""
	source = np.asarray(embedded_base_image(document, binary, material_index), dtype=np.uint8).copy()
	height, width = source.shape[:2]
	coverage, positions, normals, head_weights = rasterized_material(
		document, binary, (width, height), material_index
	)
	# Everything below is expressed along the character's own forward and right axes,
	# which the VRM coordinate table places at -Z/+X for VRM 0.x and +Z/-X for VRM 1.0.
	# Assuming either one outright builds the map on the back of the head, or mirrors
	# it, for models authored to the other version.
	forward_sign = head_forward(document)[2]
	right_sign = head_right(document)[0]
	facing = normals[..., 2] * forward_sign
	face = coverage & (facing > 0.15) & (source[..., 3] > 0)
	# The face is regularly one region of a whole-body atlas, and normalising over
	# every front-facing texel of such a material would place the cheeks somewhere
	# around the waist. Keep what the skeleton binds to the head, which is the same
	# rule the specification's first-person "auto" mode uses to split head from body,
	# and which does not depend on where the head joint happens to sit.
	# Where the skeleton retains only a sliver it is not telling us where the head is,
	# usually because the humanoid map is mis-authored, so leave the region alone.
	on_head = face & (head_weights > 0.5)
	if on_head.sum() > 0.3 * max(int(face.sum()), 1):
		face = on_head
	if not face.any():
		face = coverage & (source[..., 3] > 0)
	face_positions = positions[face]
	minimum_x, maximum_x = np.percentile(face_positions[:, 0], [2.0, 98.0])
	minimum_y, maximum_y = np.percentile(face_positions[:, 1], [2.0, 98.0])
	y = np.clip((positions[..., 1] - minimum_y) / max(maximum_y - minimum_y, 1.0e-6), 0.0, 1.0)
	center_x = 0.5 * (minimum_x + maximum_x)
	half_width = max(0.5 * (maximum_x - minimum_x), 1.0e-6)
	# Positive towards the character's right ear, so the red channel always means
	# "still lit while the light swings to the right" whichever version this model is.
	rightward = (positions[..., 0] - center_x) * right_sign
	centered_x = np.clip(rightward / half_width, -1.0, 1.0)

	# The shader lights a face pixel while `sdf >= (1 - dot(headForward, light)) / 2`,
	# so the stored value is the horizontal angle the light may rotate away from the
	# front before this pixel turns dark. Derive that angle from the head's own
	# azimuth instead of a screen-space gradient, which is what keeps the boundary
	# sweeping around the cheek rather than cutting a Lambert wedge over the nose.
	# The notional head sphere sits one radius behind the tip of the nose, which is
	# the frontmost face sample along the forward axis.
	radius = max(half_width, 1.0e-4)
	depths = face_positions[:, 2] * forward_sign
	center_depth = float(np.percentile(depths, 98.0)) - radius
	ahead = positions[..., 2] * forward_sign - center_depth
	sphere_sin = np.clip(rightward / np.maximum(np.hypot(rightward, ahead), 1.0e-6), -1.0, 1.0)
	normal_horizontal = np.maximum(np.hypot(normals[..., 0], normals[..., 2]), 1.0e-6)
	normal_sin = np.clip(normals[..., 0] * right_sign / normal_horizontal, -1.0, 1.0)
	# A little of the true normal keeps brow and cheek structure; too much brings the
	# nose triangle back.
	blended_sin = np.clip(0.78 * sphere_sin + 0.22 * normal_sin, -1.0, 1.0)
	direct = np.clip(0.5 + 0.5 * blended_sin, 0.0, 1.0)
	opposite = np.clip(0.5 - 0.5 * blended_sin, 0.0, 1.0)
	direct_sdf = np.where(face, direct, 0.0)
	opposite_sdf = np.where(face, opposite, 0.0)
	rgb = source[..., :3].astype(np.float32) / 255.0
	luminance = rgb @ np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)
	saturation = rgb.max(axis=-1) - rgb.min(axis=-1)
	(left_eye, right_eye, left_blush, right_blush) = face_feature_centers(
		face, centered_x, y, luminance, saturation
	)
	blush = np.maximum(
		gaussian(centered_x, y, *left_blush, 0.18, 0.08),
		gaussian(centered_x, y, *right_blush, 0.18, 0.08),
	)
	blush = np.where(face, np.clip(blush * 0.85, 0.0, 1.0), 0.0)
	eye_mask = np.maximum(
		gaussian(centered_x, y, *left_eye, 0.14, 0.06),
		gaussian(centered_x, y, *right_eye, 0.14, 0.06),
	)
	force_lit = np.where(face, np.clip(eye_mask * 0.7, 0.0, 1.0), 1.0)

	face_light = np.zeros((height, width, 4), dtype=np.uint8)
	face_light[..., 0] = np.rint(direct_sdf * 255.0).astype(np.uint8)
	face_light[..., 1] = np.rint(opposite_sdf * 255.0).astype(np.uint8)
	face_light[..., 2] = np.rint(blush * 255.0).astype(np.uint8)
	face_light[..., 3] = 255
	face_name = f"{prefix}-face.png"
	Image.fromarray(face_light, "RGBA").save(root / face_name, optimize=True)

	face_shadow = np.full((height, width, 4), 255, dtype=np.uint8)
	face_shadow[..., 3] = np.rint(force_lit * 255.0).astype(np.uint8)
	shadow_name = f"{prefix}-face-shadow.png"
	Image.fromarray(face_shadow, "RGBA").save(root / shadow_name, optimize=True)
	return {"faceLightMap": face_name, "faceShadow": shadow_name}


# Ramp bands are indexed the way the reference shader indexes them from the
# LightMap alpha: 0 soft common cloth, 1 skin, 2 hair, 3 metal, 4 remaining matte.
# Each entry is (deep shadow, subsurface mid tone, transition start, mid point).
RAMP_BANDS = (
	((0.60, 0.53, 0.58), (0.86, 0.75, 0.75), 0.50, 0.86),
	((0.70, 0.49, 0.49), (0.94, 0.73, 0.69), 0.44, 0.88),
	((0.53, 0.51, 0.65), (0.80, 0.76, 0.89), 0.52, 0.87),
	((0.43, 0.46, 0.58), (0.72, 0.76, 0.90), 0.62, 0.90),
	((0.58, 0.56, 0.61), (0.84, 0.80, 0.83), 0.52, 0.87),
)
HAIR_RAMP_BANDS = (
	((0.52, 0.48, 0.60), (0.80, 0.74, 0.86), 0.50, 0.86),
	((0.62, 0.47, 0.52), (0.90, 0.72, 0.72), 0.46, 0.88),
	((0.46, 0.45, 0.62), (0.76, 0.74, 0.92), 0.54, 0.88),
	((0.40, 0.43, 0.58), (0.70, 0.74, 0.90), 0.62, 0.90),
	((0.50, 0.49, 0.58), (0.80, 0.77, 0.84), 0.52, 0.87),
)
NIGHT_TINT = (0.74, 0.80, 1.00)

# The reference gates a non-metal highlight behind `lightMap.b + blinnPhong >= 1.1`,
# so shininess and the blue threshold together decide how tight each highlight is.
SPECULAR_PARAMETERS = {
	"skin": {"nonMetalSpecular": 0.15, "specularShininess": 15.0},
	"cloth": {"nonMetalSpecular": 0.40, "specularShininess": 20.0},
	"matte": {"nonMetalSpecular": 0.10, "specularShininess": 12.0},
	"hair": {"nonMetalSpecular": 0.80, "specularShininess": 30.0},
	"metal": {"metalSpecular": 0.90, "specularShininess": 50.0},
	"eye": {"nonMetalSpecular": 0.60, "specularShininess": 40.0},
	"face": {"nonMetalSpecular": 0.15, "specularShininess": 15.0},
}


def smoothstep(edge0: float, edge1: float, x: np.ndarray) -> np.ndarray:
	t = np.clip((x - edge0) / max(edge1 - edge0, 1.0e-6), 0.0, 1.0)
	return t * t * (3.0 - 2.0 * t)


def ramp_band(band: tuple, night: bool, width: int) -> np.ndarray:
	"""One ramp row: darkest at x=0, lit at x=1, transition pushed to the right."""
	deep, mid, start, middle = band
	x = np.linspace(0.0, 1.0, width, dtype=np.float32)
	deep_to_mid = smoothstep(start, middle, x)
	mid_to_lit = smoothstep(middle, 1.0, x)
	shadow = np.empty((width, 3), dtype=np.float32)
	for channel in range(3):
		shadow[:, channel] = deep[channel] + (mid[channel] - deep[channel]) * deep_to_mid
	if night:
		shadow = np.clip(shadow * np.asarray(NIGHT_TINT, dtype=np.float32) * 0.88, 0.0, 1.0)
	# The lit end has to stay neutral: the shader hard-switches to white once the
	# shadow term passes the range maximum, and a tinted right edge would show up
	# as a seam right at that switch.
	return shadow + (1.0 - shadow) * mid_to_lit[:, None]


def write_ramp(bands: tuple, name: str, root: Path) -> str:
	"""256x20 ramp in the same layout as the official Genshin shadow ramp sheets:
	five material bands per daylight state, authored for Unity's bottom-up V axis,
	which puts the daylight bands in the top half of the image."""
	width, height = 256, 20
	image = np.zeros((height, width, 4), dtype=np.float32)
	for index, band in enumerate(bands):
		top = 8 - 2 * index
		image[top : top + 2, :, :3] = ramp_band(band, False, width)
		image[top + 10 : top + 12, :, :3] = ramp_band(band, True, width)
	image[..., 3] = 1.0
	encoded = np.rint(np.clip(image, 0.0, 1.0) * 255.0).astype(np.uint8)
	Image.fromarray(encoded, "RGBA").save(root / name, optimize=True)
	return name


def write_ramps(root: Path, prefix: str) -> tuple[str, str]:
	return (
		write_ramp(RAMP_BANDS, f"{prefix}-ramp.png", root),
		write_ramp(HAIR_RAMP_BANDS, f"{prefix}-ramp-hair.png", root),
	)


def write_matcap(root: Path, prefix: str) -> str:
	size = 256
	yy, xx = np.mgrid[0:size, 0:size]
	u = (xx / (size - 1)) * 2.0 - 1.0
	v = (yy / (size - 1)) * 2.0 - 1.0
	r = np.sqrt(u * u + v * v)
	mask = r <= 1.0
	spec = np.clip(1.0 - r, 0.0, 1.0) ** 3
	image = np.zeros((size, size, 4), dtype=np.uint8)
	image[..., 0] = np.where(mask, np.rint(180 + 75 * spec), 0).astype(np.uint8)
	image[..., 1] = np.where(mask, np.rint(185 + 70 * spec), 0).astype(np.uint8)
	image[..., 2] = np.where(mask, np.rint(200 + 55 * spec), 0).astype(np.uint8)
	image[..., 3] = np.where(mask, 255, 0).astype(np.uint8)
	name = f"{prefix}-matcap-metal.png"
	Image.fromarray(image, "RGBA").save(root / name, optimize=True)
	return name


def head_forward(document: dict) -> list[float]:
	"""Per the VRM coordinate table, VRM 0.x faces -Z with +X to the right while
	VRM 1.0 faces +Z with -X to the right."""
	return [0.0, 0.0, 1.0] if is_vrm1(document) else [0.0, 0.0, -1.0]


def head_right(document: dict) -> list[float]:
	return [-1.0, 0.0, 0.0] if is_vrm1(document) else [1.0, 0.0, 0.0]


def is_vrm1(document: dict) -> bool:
	return "VRMC_vrm" in document.get("extensions", {})


def generate(model: Path, root: Path, prefix: str, sidecar: Path) -> None:
	document, binary = load_glb(model)
	materials = document["materials"]
	body_ramp, hair_ramp = write_ramps(root, prefix)
	matcap = write_matcap(root, prefix)
	categories, face_index = classify_materials(document, binary)
	if face_index is None:
		print(f"warning: {model.name} has no identifiable face material; "
			"the facial SDF path is left unconfigured rather than approximated")
		face_maps = {}
	else:
		face_maps = write_face_assets(document, binary, face_index, root, prefix)

	profile_materials = []
	for index, material in enumerate(materials):
		category = categories[index]
		light = write_light_map(document, binary, index, category, root, prefix)
		entry: dict = {
			"index": index,
			"lightMap": light,
			"rampTexture": hair_ramp if category == "hair" else body_ramp,
			"outline": True,
			"outlineMode": "screen",
			# Screen-space outlines resolve to roughly `2 * outlineWidth` pixels, so
			# anything below about 0.5 breaks up into a dotted line.
			"outlineWidth": 1.10 if category in {"hair", "cloth"} else 0.90,
			"outlineZOffset": 1.0,
			"outlineColor": outline_tint(document, binary, index),
			# The reference leaves the terminator at half Lambert and widens it with
			# smoothness alone; the ramp gradient is only visible across this band.
			"shadowOffset": 0.0,
			"shadowSmoothness": 0.30,
			"shadeColor": [1.0, 1.0, 1.0, 1.0],
			# `rimOffset` is a screen-space pixel offset, so it has to be sized in
			# pixels for the depth difference to register at all.
			"rimOffset": 4.0,
			"rimThreshold": 0.15,
			"rimIntensity": 0.45,
			"rimPower": 5.0,
			**SPECULAR_PARAMETERS[category],
		}
		if index == face_index:
			entry["face"] = True
			entry["faceSdfLayout"] = "directional-rg"
			entry["faceLightMap"] = face_maps["faceLightMap"]
			entry["faceShadow"] = face_maps["faceShadow"]
			entry["blushIntensity"] = 0.45
			entry["faceShadowStrength"] = 1.0
		elif category == "hair":
			entry["rimOffset"] = 5.0
			entry["rimIntensity"] = 0.6
		elif category == "metal":
			entry["matcapTexture"] = matcap
			entry["metallic"] = True
		profile_materials.append(entry)

	profile = {
		"version": 2,
		# The head node itself is left out so the loader resolves it from the VRM
		# humanoid extension, which works for both VRM 0.x and VRM 1.0.
		"head": {"forward": head_forward(document), "right": head_right(document)},
		"lightDirectionMultiplier": [1.0, 0.55, 1.0],
		"smoothNormals": "generate",
		"smoothNormalAngle": 180,
		"baseColorScale": 1.0,
		"rampTexture": body_ramp,
		"materials": profile_materials,
	}
	sidecar.write_text(json.dumps(profile, indent=2) + "\n", encoding="utf-8")
	print(f"wrote {sidecar.name} with {len(profile_materials)} materials")


def main() -> None:
	parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
	parser.add_argument("model", type=Path, help="path to the .vrm file")
	parser.add_argument("--output", type=Path,
		help="directory for the generated textures (default: next to the model)")
	parser.add_argument("--prefix",
		help="file name prefix for the generated textures (default: the model stem)")
	parser.add_argument("--sidecar", type=Path,
		help="path of the profile to write (default: <model>.toon.json)")
	arguments = parser.parse_args()
	model = arguments.model.resolve()
	root = (arguments.output or model.parent).resolve()
	root.mkdir(parents=True, exist_ok=True)
	prefix = arguments.prefix or f"{model.stem.lower()}-toon"
	sidecar = arguments.sidecar or model.with_name(f"{model.name}.toon.json")
	generate(model, root, prefix, sidecar)


if __name__ == "__main__":
	main()
