#!/usr/bin/env python3
"""Regenerate the CC0 Sendagaya Shino ToonShader sidecar.

Everything is derived by the shared VRM tool; this file only names the model and
the texture prefix used by the committed example.
"""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parents[2] / "scripts"))

from vrm_toon_assets import generate  # noqa: E402

MODEL = ROOT / "Sendagaya_Shino.vrm"

if __name__ == "__main__":
	generate(MODEL, ROOT, "sendagaya-toon", ROOT / "Sendagaya_Shino.vrm.toon.json")
