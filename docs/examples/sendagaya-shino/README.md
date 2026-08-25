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
the model. Face SDF is procedural (no third-party textures). Tune
`baseColorScale`, ramps, and face SDF in the script, then regenerate.

## Capture README still

```bash
export CELERANT_VISUAL_VRM=$PWD/Sendagaya_Shino.vrm
./scripts/toon-run-pack.sh 02-complementary-unbound
```

Use a Toon ON frame with the ShaderPack active as
`docs/images/example-sendagaya-shino.png`. Compare against
`kaze-mio/UnityGenshinToonShader` `Images/image_0.png` for soft cheek shadow,
warm lit albedo, thin outlines, and bounded rim.
