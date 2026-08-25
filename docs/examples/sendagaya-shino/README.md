# Sendagaya Shino ToonShader example assets

CC0 VRoid sample **Sendagaya Shino**, prepared like the Jingburger matrix
fixtures: procedural LightMaps, face SDF, body/hair ramps, and a v2
`.toon.json` sidecar.

## Download the VRM

```bash
curl -fsSL -o Sendagaya_Shino.vrm.zip \
  https://opengameart.org/sites/default/files/sendagaya_shino.zip
unzip -o Sendagaya_Shino.vrm.zip
mv "Sendagaya Shino.vrm" Sendagaya_Shino.vrm
```

License: [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/)
([VRoid terms](https://vroid.pixiv.help/hc/en-us/articles/4402614652569-Do-VRoid-Studio-s-sample-models-come-with-conditions-of-use) /
[OpenGameArt mirror](https://opengameart.org/content/vroid-studio-cc0-models)).

## Generate maps

```bash
python3 -m venv .venv && .venv/bin/pip install pillow numpy
.venv/bin/python generate_toon_assets.py
```

This writes `Sendagaya_Shino.vrm.toon.json` plus `sendagaya-toon-*.png` beside
the model. Face SDF is procedural (no third-party textures).

`generate_toon_assets.py` only names this model and its texture prefix. All of
the derivation lives in [`scripts/vrm_toon_assets.py`](../../../scripts/vrm_toon_assets.py),
which takes any VRM:

```bash
.venv/bin/python ../../../scripts/vrm_toon_assets.py /path/to/model.vrm
```

Nothing is keyed to a model or to material names. The face material is found
from the mesh the VRM blink and vowel expressions drive, the head is separated
by the head-bone skin weights that the specification's first-person `auto` rule
uses, and the forward/right axes follow the VRM coordinate table, which is `-Z`
/`+X` for VRM 0.x and `+Z`/`-X` for VRM 1.0. Tune ramps, light parameters, and
the face SDF in the shared script, then regenerate.

## Check the sidecar

`verify_toon_assets.py` reports what this example's maps hold, sampled the way
the shader samples them: the mirrored face SDF pair, the lit fraction as the
light swings across the head, and each material's shadow threshold and ramp row.

```bash
.venv/bin/python verify_toon_assets.py
```

To check the derivation against many models instead of one, point the corpus
script at directories of VRMs. It classifies every model, generates the full
sidecar with `--build`, and fails if a character's face cannot be found, if the
two face SDF channels stop being complementary, if a ramp runs light-to-shadow
instead of shadow-to-light, or if the lit sweep stops following the character's
right axis. Models that are feature-test scenes rather than avatars are
reported as skipped.

```bash
.venv/bin/python ../../../scripts/check_vrm_corpus.py /path/to/vrms --build /tmp/out
```

## Capture README still

```bash
export CELERANT_VISUAL_VRM=$PWD/Sendagaya_Shino.vrm
./scripts/toon-run-pack.sh 02-complementary-unbound
```

Use a Toon ON frame with the ShaderPack active as
`docs/images/example-sendagaya-shino.png`. Compare against
`kaze-mio/UnityGenshinToonShader` `Images/image_0.png` for soft cheek shadow,
warm lit albedo, thin outlines, and bounded rim.
