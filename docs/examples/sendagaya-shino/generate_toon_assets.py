#!/usr/bin/env python3
"""Generate CC0 Sendagaya Shino ToonShader sidecar assets (procedural; no third-party textures)."""

from __future__ import annotations

import io
import json
import struct
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent
MODEL = ROOT / "Sendagaya_Shino.vrm"
PREFIX = "sendagaya-toon"


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
	best = np.full((height, width), -np.inf, dtype=np.float32)
	positions = np.zeros((height, width, 3), dtype=np.float32)
	normals = np.zeros((height, width, 3), dtype=np.float32)
	for mesh in document["meshes"]:
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
				score = -interpolated_normals[..., 2]
				rows = slice(minimum[1], maximum[1] + 1)
				columns = slice(minimum[0], maximum[0] + 1)
				update = inside & (score > best[rows, columns])
				if not update.any():
					continue
				best[rows, columns][update] = score[update]
				interpolated_positions = weights @ source_positions[[a, b, c]]
				positions[rows, columns][update] = interpolated_positions[update]
				normals[rows, columns][update] = interpolated_normals[update]
	coverage = np.isfinite(best)
	if not coverage.any() and not allow_empty:
		raise ValueError(f"material {material_index} has no rasterizable UV triangles")
	return coverage, positions, normals


def gaussian(x: np.ndarray, y: np.ndarray, cx: float, cy: float, rx: float, ry: float) -> np.ndarray:
	return np.exp(-0.5 * (((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2))


LIGHT_PARAMETERS = {
	"skin": (0.50, 0.58, 0.03, 0.10, 0.12, 0.30),
	"cloth": (0.44, 0.56, 0.07, 0.18, 0.48, 0.70),
	"matte": (0.46, 0.55, 0.02, 0.08, 0.12, 0.10),
	"hair": (0.46, 0.54, 0.24, 0.58, 0.56, 0.50),
	"metal": (0.42, 0.52, 0.94, 1.00, 0.96, 0.95),
	"eye": (0.48, 0.55, 0.35, 0.70, 0.40, 0.20),
	"face": (0.50, 0.58, 0.03, 0.10, 0.12, 0.30),
}


def categorize(name: str) -> str:
	upper = name.upper()
	if "HAIR" in upper:
		return "hair"
	if "EYE" in upper or "IRIS" in upper or "HIGHLIGHT" in upper:
		return "eye"
	if "SKIN" in upper or "BODY" in upper:
		return "skin"
	if "FACE" in upper or "MOUTH" in upper or "BROW" in upper or "EYEL" in upper:
		return "face"
	if "SHOE" in upper or "ACCESSORY" in upper:
		return "metal"
	return "cloth"


def write_light_map(document: dict, binary: bytes, material_index: int, category: str) -> str:
	size = (512, 512)
	coverage, _, normals = rasterized_material(
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
	name = f"{PREFIX}-light-{material_index}.png"
	Image.fromarray(encoded, "RGBA").save(ROOT / name, optimize=True)
	return name


def write_face_assets(document: dict, binary: bytes, material_index: int) -> dict:
	"""Procedural face LightMap / shadow SDF (no third-party textures)."""
	source = np.asarray(embedded_base_image(document, binary, material_index), dtype=np.uint8).copy()
	height, width = source.shape[:2]
	coverage, positions, normals = rasterized_material(document, binary, (width, height), material_index)
	face = coverage & (normals[..., 2] < -0.15) & (source[..., 3] > 0)
	if not face.any():
		face = coverage & (source[..., 3] > 0)
	face_positions = positions[face]
	minimum_x, maximum_x = np.percentile(face_positions[:, 0], [2.0, 98.0])
	minimum_y, maximum_y = np.percentile(face_positions[:, 1], [2.0, 98.0])
	x = np.clip((positions[..., 0] - minimum_x) / max(maximum_x - minimum_x, 1.0e-6), 0.0, 1.0)
	y = np.clip((positions[..., 1] - minimum_y) / max(maximum_y - minimum_y, 1.0e-6), 0.0, 1.0)
	centered_x = x * 2.0 - 1.0
	# Genshin-like horizontal face SDF: soft cheek bands, strong nose fill.
	raw = np.clip(0.5 - 0.5 * centered_x, 0.0, 1.0)
	direct = raw * raw * (3.0 - 2.0 * raw)  # smoothstep
	direct = np.clip(0.18 + 0.72 * direct, 0.0, 1.0)
	nose = np.clip(1.0 - np.abs(centered_x) / 0.28, 0.0, 1.0) * np.clip(1.0 - np.abs(y - 0.48) / 0.22, 0.0, 1.0)
	direct = np.clip(direct + 0.28 * nose, 0.0, 1.0)
	opp_raw = np.clip(0.5 + 0.5 * centered_x, 0.0, 1.0)
	opposite = opp_raw * opp_raw * (3.0 - 2.0 * opp_raw)
	opposite = np.clip(0.18 + 0.72 * opposite, 0.0, 1.0)
	opposite = np.clip(opposite + 0.28 * nose, 0.0, 1.0)
	direct_sdf = np.where(face, direct, 0.0)
	opposite_sdf = np.where(face, opposite, 0.0)
	blush = np.maximum(
		gaussian(centered_x, y, -0.38, 0.34, 0.18, 0.08),
		gaussian(centered_x, y, 0.38, 0.34, 0.18, 0.08),
	)
	blush = np.where(face, np.clip(blush * 0.85, 0.0, 1.0), 0.0)
	eye_mask = np.maximum(
		gaussian(centered_x, y, -0.28, 0.52, 0.14, 0.06),
		gaussian(centered_x, y, 0.28, 0.52, 0.14, 0.06),
	)
	force_lit = np.where(face, np.clip(eye_mask * 0.7, 0.0, 1.0), 1.0)

	face_light = np.zeros((height, width, 4), dtype=np.uint8)
	face_light[..., 0] = np.rint(direct_sdf * 255.0).astype(np.uint8)
	face_light[..., 1] = np.rint(opposite_sdf * 255.0).astype(np.uint8)
	face_light[..., 2] = np.rint(blush * 255.0).astype(np.uint8)
	face_light[..., 3] = 255
	face_name = f"{PREFIX}-face.png"
	Image.fromarray(face_light, "RGBA").save(ROOT / face_name, optimize=True)

	face_shadow = np.full((height, width, 4), 255, dtype=np.uint8)
	face_shadow[..., 3] = np.rint(force_lit * 255.0).astype(np.uint8)
	shadow_name = f"{PREFIX}-face-shadow.png"
	Image.fromarray(face_shadow, "RGBA").save(ROOT / shadow_name, optimize=True)
	return {"faceLightMap": face_name, "faceShadow": shadow_name}


def write_ramps() -> tuple[str, str]:
	"""Procedural 256x20 ramps approximating Genshin body/hair shadow bands."""
	width, height = 256, 20
	body = np.zeros((height, width, 4), dtype=np.uint8)
	hair = np.zeros((height, width, 4), dtype=np.uint8)
	for x in range(width):
		t = x / (width - 1)
		# Body: warm lit → soft mid → cool shadow (wide soft transition)
		if t < 0.38:
			rgb = (255, 240, 230)
		elif t < 0.62:
			mix = (t - 0.38) / 0.24
			mix = mix * mix * (3.0 - 2.0 * mix)
			rgb = tuple(int(a * (1 - mix) + b * mix) for a, b in zip((255, 240, 230), (205, 160, 148)))
		else:
			mix = min(1.0, (t - 0.62) / 0.38)
			mix = mix * mix * (3.0 - 2.0 * mix)
			rgb = tuple(int(a * (1 - mix) + b * mix) for a, b in zip((205, 160, 148), (130, 105, 120)))
		body[:, x, :3] = rgb
		body[:, x, 3] = 255
		# Hair: cooler highlight band then deep shadow
		if t < 0.32:
			rgb = (110, 120, 155)
		elif t < 0.58:
			mix = (t - 0.32) / 0.26
			mix = mix * mix * (3.0 - 2.0 * mix)
			rgb = tuple(int(a * (1 - mix) + b * mix) for a, b in zip((110, 120, 155), (50, 55, 80)))
		else:
			mix = min(1.0, (t - 0.58) / 0.42)
			mix = mix * mix * (3.0 - 2.0 * mix)
			rgb = tuple(int(a * (1 - mix) + b * mix) for a, b in zip((50, 55, 80), (22, 24, 38)))
		hair[:, x, :3] = rgb
		hair[:, x, 3] = 255
	body_name = f"{PREFIX}-ramp.png"
	hair_name = f"{PREFIX}-ramp-hair.png"
	Image.fromarray(body, "RGBA").save(ROOT / body_name, optimize=True)
	Image.fromarray(hair, "RGBA").save(ROOT / hair_name, optimize=True)
	return body_name, hair_name


def write_matcap() -> str:
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
	name = f"{PREFIX}-matcap-metal.png"
	Image.fromarray(image, "RGBA").save(ROOT / name, optimize=True)
	return name


def main() -> None:
	document, binary = load_glb(MODEL)
	materials = document["materials"]
	body_ramp, hair_ramp = write_ramps()
	matcap = write_matcap()
	face_index = next(
		(i for i, m in enumerate(materials) if "Face_00_SKIN" in m.get("name", "")),
		7,
	)
	face_maps = write_face_assets(document, binary, face_index)

	profile_materials = []
	for index, material in enumerate(materials):
		name = material.get("name", f"mat{index}")
		category = categorize(name)
		light = write_light_map(document, binary, index, category)
		entry: dict = {
			"index": index,
			"lightMap": light,
			"outline": True,
			"outlineMode": "screen",
			"outlineWidth": 0.45 if category in {"hair", "cloth"} else 0.35,
			"outlineColor": [0.22, 0.18, 0.22, 1.0],
			"shadowSmoothness": 0.08,
		}
		if index == face_index:
			entry["face"] = True
			entry["faceSdfLayout"] = "directional-rg"
			entry["faceLightMap"] = face_maps["faceLightMap"]
			entry["faceShadow"] = face_maps["faceShadow"]
			entry["rampTexture"] = body_ramp
			entry["blushIntensity"] = 0.45
			entry["faceShadowStrength"] = 0.75
			entry["shadowSmoothness"] = 0.12
		elif category == "hair":
			entry["rampTexture"] = hair_ramp
			entry["rimIntensity"] = 0.55
			entry["rimOffset"] = 0.12
			entry["rimPower"] = 3.0
		elif category == "skin":
			entry["rampTexture"] = body_ramp
		elif category == "metal":
			entry["matcapTexture"] = matcap
			entry["metalSpecular"] = 0.9
			entry["metallic"] = True
		elif category == "cloth":
			entry["rampTexture"] = body_ramp
			entry["rimIntensity"] = 0.4
			entry["rimOffset"] = 0.1
			entry["rimPower"] = 2.5
		profile_materials.append(entry)

	profile = {
		"version": 2,
		"head": {"name": "J_Bip_C_Head", "forward": [0, 0, -1], "right": [1, 0, 0]},
		"lightDirectionMultiplier": [1.0, 0.55, 1.0],
		"smoothNormals": "generate",
		"smoothNormalAngle": 180,
		"baseColorScale": 0.62,
		"rampTexture": body_ramp,
		"materials": profile_materials,
	}
	sidecar = ROOT / "Sendagaya_Shino.vrm.toon.json"
	sidecar.write_text(json.dumps(profile, indent=2) + "\n", encoding="utf-8")
	print(f"wrote {sidecar.name} with {len(profile_materials)} materials")


if __name__ == "__main__":
	main()
