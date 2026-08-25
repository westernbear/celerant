#!/usr/bin/env python3
"""Check the generated sidecar data against the reference shader's sampling.

The reference `GetShadowColor` reads the ramp at `index / 10 + 0.5 * day + 0.05`
and `GetShadow` compares `(NdotL + 1) * ao` against `0.5 + shadowOffset`, so the
data is only correct if it lines up with those exact lookups.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent
BANDS = ("cloth/soft", "skin", "hair", "metal", "matte")


def sample(image: np.ndarray, u: float, v: float) -> tuple[int, int, int]:
	height, width = image.shape[:2]
	x = min(width - 1, max(0, int(u * width)))
	y = min(height - 1, max(0, int(v * height)))
	return tuple(int(c) for c in image[y, x, :3])


def report_ramp(name: str) -> None:
	image = np.asarray(Image.open(ROOT / name).convert("RGBA"))
	print(f"\n{name}  {image.shape[1]}x{image.shape[0]}")
	for day, label in ((1.0, "day"), (0.0, "night")):
		for index, band in enumerate(BANDS):
			v = 1.0 - (index / 10.0 + 0.5 * day + 0.05)
			shadow = sample(image, 0.0, v)
			edge = sample(image, 0.8, v)
			lit = sample(image, 0.999, v)
			print(
				f"  {label:5s} row {index} {band:10s} v={v:.2f}"
				f"  deep={shadow}  edge={edge}  lit={lit}"
			)


def report_face() -> None:
	image = np.asarray(Image.open(ROOT / "sendagaya-toon-face.png").convert("RGBA"))
	direct = image[..., 0].astype(np.float32) / 255.0
	opposite = image[..., 1].astype(np.float32) / 255.0
	covered = (direct + opposite) > 0.02
	print(f"\nface SDF covered texels: {int(covered.sum())}")
	print(f"  R range {direct[covered].min():.3f}..{direct[covered].max():.3f}")
	print(f"  G range {opposite[covered].min():.3f}..{opposite[covered].max():.3f}")
	mirror_error = float(np.abs(direct[covered] + opposite[covered] - 1.0).mean())
	print(f"  mean |R + G - 1| = {mirror_error:.4f} (mirrored pair should be ~0)")
	for threshold in (0.25, 0.5, 0.75):
		lit = float((direct[covered] >= threshold).mean())
		print(f"  lit fraction at light threshold {threshold:.2f}: {lit:.3f}")


def report_light_maps() -> None:
	profile = json.loads((ROOT / "Sendagaya_Shino.vrm.toon.json").read_text())
	print("\nlight maps (green = shadow threshold, alpha = ramp selector)")
	for material in profile["materials"]:
		image = np.asarray(Image.open(ROOT / material["lightMap"]).convert("RGBA"))
		green = image[..., 1].astype(np.float32) / 255.0
		alpha = float(np.median(image[..., 3])) / 255.0
		index = 4
		for threshold, value in ((0.2, 1), (0.4, 2), (0.6, 0), (0.8, 3)):
			if alpha >= threshold:
				index = value
		print(
			f"  material {material['index']:2d}  green {green.min():.2f}..{green.max():.2f}"
			f"  selector {alpha:.2f} -> ramp row {index} ({BANDS[index]})"
		)


if __name__ == "__main__":
	report_ramp("sendagaya-toon-ramp.png")
	report_ramp("sendagaya-toon-ramp-hair.png")
	report_face()
	report_light_maps()
