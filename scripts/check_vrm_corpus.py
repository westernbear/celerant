#!/usr/bin/env python3
"""Run the VRM toon classification over a corpus of models and report what it found.

Generalisation is the property under test here: the same code has to locate the face,
the hair and the body on every model without per-model configuration, so this prints
one row per model and fails loudly on anything it cannot classify.
"""

from __future__ import annotations

import argparse
import sys
import traceback
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from vrm_toon_assets import (  # noqa: E402
	classify_materials,
	expression_meshes,
	head_forward,
	head_node_index,
	head_right,
	is_vrm1,
	load_glb,
	material_geometry,
)
from vrm_toon_assets import (  # noqa: E402
	embedded_base_image,
	generate,
	rasterized_material,
)


def build(path: Path, output: Path) -> None:
	"""Run the full sidecar generation and check the result against the shader's own
	expectations, which is what catches models the derivation cannot actually serve."""
	import json

	import numpy as np
	from PIL import Image

	root = output / path.stem.replace(" ", "_")
	root.mkdir(parents=True, exist_ok=True)
	sidecar = root / f"{path.name}.toon.json"
	generate(path, root, "toon", sidecar)
	profile = json.loads(sidecar.read_text())
	rows = set()
	for material in profile["materials"]:
		light = np.asarray(Image.open(root / material["lightMap"]).convert("RGBA"))
		green = light[..., 1].astype(np.float32) / 255.0
		alpha = float(np.median(light[..., 3])) / 255.0
		index = 4
		for threshold, value in ((0.2, 1), (0.4, 2), (0.6, 0), (0.8, 3)):
			if alpha >= threshold:
				index = value
		rows.add(index)
		assert 0.0 <= green.min() and green.max() <= 1.0, "light map green out of range"
	face = next((m for m in profile["materials"] if m.get("face")), None)
	if face is not None:
		light = np.asarray(Image.open(root / face["faceLightMap"]).convert("RGBA"))
		red = light[..., 0].astype(np.float32) / 255.0
		green = light[..., 1].astype(np.float32) / 255.0
		painted = (red + green) > 0.02
		assert abs((red + green)[painted] - 1.0).max() < 0.01, \
			"the two sweep channels must stay complementary"
		# Red has to mean "lit from the character's right" on every model. Checking that
		# in the texture's own columns only works when the atlas happens to lay the face
		# out left to right, so compare against the character's right axis in object
		# space, which is what the shader will actually light.
		document, binary = load_glb(path)
		_, face_index = classify_materials(document, binary)
		source = np.asarray(embedded_base_image(document, binary, face_index))
		_, positions, _, _ = rasterized_material(
			document, binary, (source.shape[1], source.shape[0]), face_index
		)
		rightward = positions[..., 0] * head_right(document)[0]
		correlation = np.corrcoef(red[painted], rightward[painted])[0, 1]
		assert correlation > 0.5, \
			f"the lit sweep must follow the character's right axis, got {correlation:+.3f}"
		print(f"     face sweep follows the right axis at {correlation:+.3f} "
			f"over {painted.mean():.1%} of the sheet")
	ramp = np.asarray(Image.open(root / profile["rampTexture"]).convert("RGBA"))
	assert ramp.shape[:2] == (20, 256), f"ramp is {ramp.shape[:2]}, expected (20, 256)"
	for day in (0.0, 1.0):
		for index in rows:
			v = 1.0 - (index / 10.0 + 0.5 * day + 0.05)
			row = ramp[min(19, int(v * 20))]
			assert int(row[0, :3].mean()) < int(row[255, :3].mean()), \
				"ramp must run from shadow at u=0 to light at u=1"
	print(f"     built {len(profile['materials'])} materials, ramp rows used {sorted(rows)}")


def check(path: Path, verbose: bool) -> str:
	document, binary = load_glb(path)
	categories, face = classify_materials(document, binary)
	geometry = material_geometry(document, binary)
	materials = document.get("materials", [])
	counts = Counter(categories.values())
	version = "VRM1.0" if is_vrm1(document) else "VRM0.x"
	forward = "+Z" if head_forward(document)[2] > 0 else "-Z"
	right = "+X" if head_right(document)[0] > 0 else "-X"
	# Some specification samples are feature test scenes rather than avatars. Without
	# facial expressions or geometry attached to the head there is no face to find,
	# and reporting that is the correct outcome rather than a miss.
	character = bool(expression_meshes(document)) or any(
		entry["head_area"] / max(entry["area"], 1.0e-9) > 0.5 for entry in geometry.values()
	)
	face_name = materials[face].get("name", "?") if face is not None else "NOT FOUND"
	status = "ok  " if face is not None else ("skip" if not character else "FAIL")
	print(f"{status} {path.name[:38]:38s} {version} {forward}/{right} "
		f"head={head_node_index(document)} exprMesh={sorted(expression_meshes(document)) or '-'} "
		f"materials={len(materials):2d} face={face} '{face_name[:28]}'")
	print(f"       {dict(sorted(counts.items()))}")
	if verbose:
		for index, material in enumerate(materials):
			print(f"         {index:2d} {categories[index]:6s} {material.get('name', '?')}")
	return status.strip()


def main() -> None:
	parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
	parser.add_argument("roots", nargs="+", type=Path)
	parser.add_argument("--verbose", action="store_true")
	parser.add_argument("--build", type=Path,
		help="also generate the full sidecar for every character into this directory")
	arguments = parser.parse_args()
	models: list[Path] = []
	for root in arguments.roots:
		models.extend(sorted(root.rglob("*.vrm")) if root.is_dir() else [root])
	results: Counter[str] = Counter()
	for model in models:
		try:
			status = check(model, arguments.verbose)
			results[status] += 1
			if arguments.build and status == "ok":
				build(model, arguments.build)
		except Exception:
			results["ERROR"] += 1
			print(f"ERROR {model.name}")
			traceback.print_exc()
	characters = results["ok"] + results["FAIL"]
	print(f"\n{results['ok']}/{characters} characters classified a face material, "
		f"{results['skip']} non-character scenes skipped, {results['ERROR']} errors")
	sys.exit(0 if results["FAIL"] == 0 and results["ERROR"] == 0 else 1)


if __name__ == "__main__":
	main()
