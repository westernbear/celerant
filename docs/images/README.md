# Example images

Committed README images use only **CC0** VRM models. The repository does not
ship copyrighted character assets in `docs/images/`.

`example-sendagaya-shino.png` shows the VRoid Project **Sendagaya Shino** sample
VRM in Minecraft with Celerant + MCglTF ToonShader and Complementary Unbound
(Iris) active, using the generated sidecar under
`docs/examples/sendagaya-shino/`.

- Model: Sendagaya Shino (VRoid Studio sample, CC0)
- License: [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/)
- Source: [VRoid sample model terms](https://vroid.pixiv.help/hc/en-us/articles/4402614652569-Do-VRoid-Studio-s-sample-models-come-with-conditions-of-use)
  / [OpenGameArt mirror](https://opengameart.org/content/vroid-studio-cc0-models)
- ShaderPack: Complementary Unbound (Modrinth, Iris)
- Toon data: procedural LightMaps / face SDF / ramps via `generate_toon_assets.py`
- Capture: MCglTF 2.3.2.8, Complementary Unbound r5.8.1, Toon ON with Iris active
- Visual baseline: [UnityGenshinToonShader](https://github.com/kaze-mio/UnityGenshinToonShader) `Images/image_0.png`

The `.vrm` itself is not vendored; download and regenerate maps as described in
`docs/examples/sendagaya-shino/README.md`.
