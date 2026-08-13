## Research before execution

- Before starting any new task, complete relevant web and literature research first, then plan and execute the work.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

When the user types `/graphify`, use the installed graphify skill or instructions before doing anything else.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).

## ToonShader acceptance criteria

- VRM toon work must produce a recognizably Genshin-style result, using `kaze-mio/UnityGenshinToonShader` and its reference images as the visual baseline. A generic Iris ShaderPack look, simple MToon darkening, or a two-band color ramp alone is not complete.
- The target includes smooth character shading without visible low-poly normal facets, material-aware shadow ramps, intentional face shading, rim lighting, and character outlines. If required model data such as a LightMap, face SDF, smooth normals, or outline controls is absent, add an explicit compatible data path or report the feature as incomplete; do not silently substitute a generic effect.
- Completion requires Jingburger VRM screenshots rendered with a real current Modrinth Iris ShaderPack enabled, with toon ON/OFF/restored comparisons. Inspect the images directly and do not claim success unless the ON image is materially closer to the Genshin reference while the ShaderPack remains active.
- Preserve unknown ShaderPack G-buffer semantics and source files. Unsupported attachment contracts must remain fail-closed, and visual gains must be measured alongside frame-time impact.
- Prioritize general Iris, GLSL, VRM, and MToon contracts over ShaderPack-specific behavior. Detect output locations, types, and material inputs from the transformed program; do not tune colors, thresholds, light directions, or shader behavior by pack name or archive hash.
- A pack-specific codec is a last resort permitted only for a genuinely unique, documented storage format whose non-color bits are preserved exactly. Keep the Genshin material model shared, keep unknown formats fail-closed, and validate compatibility changes across representative packs so a single-pack screenshot cannot define the implementation.

## Mandatory ToonShader visual evaluation

Use these items as test criteria, not as optional review notes. The official
`kaze-mio/UnityGenshinToonShader` `Images/image_0.png` and `Images/image_1.png`
are the reference images. A result is incomplete if any visual item below fails.

### Capture protocol

- Test the Jingburger VRM with its face map, material LightMaps, ramp, matcap,
  smooth-normal data, rim controls, and outline controls present. Missing or
  unread inputs are a test failure, not permission to use a generic fallback.
- Test current Modrinth releases of BSL, Complementary Reimagined, and
  Complementary Unbound. For each pack, capture Toon ON, Toon OFF, and restored
  ON without changing the camera, pose, time, weather, exposure, or ShaderPack
  settings between those three captures.
- With Toon ON, also capture the face under two opposed horizontal light/head
  directions so the mirrored face-SDF response is observable rather than
  inferred from one frontal frame.
- Capture both a full-character view and close crops of the face, hair, white
  cloth, skin, and metallic/detail materials. Compare at native resolution and
  at 4x nearest-neighbour magnification so broken lines and normal facets remain
  visible. Normalize character height when placing the official reference and
  test capture side by side.
- Inspect every image directly. Pixel-delta detection, a successful shader
  compile, or an ON/OFF difference alone cannot pass the visual evaluation.

### Minecraft test memory safety

- Never test multiple representative ShaderPacks in one Minecraft JVM. Run BSL,
  Complementary Reimagined, and Complementary Unbound one at a time in fresh
  Gradle/Minecraft processes; never run Minecraft, Gradle tests, or visual
  browser QA concurrently.
- Before every launch, verify no earlier Celerant Minecraft, Gradle daemon, or
  Xvfb process remains and require at least 10 GiB `MemAvailable`. Do not stop
  unrelated user services to make room without explicit approval.
- Run the test scope with `MemoryHigh=9G`, `MemoryMax=10G`, and
  `MemorySwapMax=0`, and run Gradle with `--no-daemon --max-workers=1`. The
  supported wrapper form is `systemd-run --user --scope -p MemoryHigh=9G -p
  MemoryMax=10G -p MemorySwapMax=0 -- <test command>`; do not combine `--scope`
  with the incompatible `--wait` option.
- A memory-limit kill is a failed/incomplete test, not a reason to raise the
  limit. Preserve completed evidence, inspect memory growth, and retry only the
  unfinished ShaderPack in a fresh process.
- Each single-pack run truncates the matrix TSV and reuses row/image names, so
  archive its TSV and screenshots before starting the next pack. Assemble the
  final cross-pack report only from those preserved per-pack artifacts.

### Binary visual gates

- **Face SDF:** facial light and shadow must respond directionally to the head
  forward/right basis and light direction. Skin must retain a broad clean light
  region, controlled cheek/jaw shadow, and subtle blush. A symmetric red eye or
  cheek band, Lambert-style nose/triangle shading, or unchanged face shadow when
  light crosses the head is a failure.
- **Material-aware ramps:** skin, hair, cloth, emissive/detail, and metal must
  use visibly distinct authored responses. White cloth must retain highlight,
  base, and shadow separation without clipping into a featureless white mass.
  A shared two-band darkening operation or isolated brown patches is a failure.
- **Smooth normals:** sleeves, torso, hips, legs, face, and hair must not expose
  repeated triangle boundaries or low-poly normal facets in either the interior
  shading or outline direction. Visible faceting at native resolution or in the
  required 4x crop is a failure.
- **Outlines:** silhouette and intentional internal outlines must be continuous,
  stable, material-coloured, and approximately constant in screen-space width.
  Dotted or broken lines, black pixel noise, alpha-edge speckling, halos,
  z-fighting, or missing outlines on bright materials are failures.
- **Rim and specular:** rim light must be thin, bounded, and directionally
  plausible rather than a broad Fresnel glow. Hair needs a controlled highlight
  response; metallic materials need localized strong highlights or matcap
  response; skin must remain predominantly matte. Effects that cannot be
  distinguished in the close crops fail.
- **Colour and compositing:** the character must preserve authored hue and
  dynamic range while the active ShaderPack scene remains unchanged outside the
  character and its outline. Washed-out albedo, near-white clipping, unintended
  darkening of the whole model, or modification of ShaderPack colour/G-buffer
  semantics is a failure.

### Technical and verdict gates

- Source ZIP SHA-256 must remain unchanged, the copied source hash must match,
  `patched_entity_programs` must remain `0`, Iris must report the selected pack
  active in ON/OFF/restored captures, and unknown attachment contracts must
  remain fail-closed.
- Restored ON must reproduce the initial ON character pixels within the existing
  stability tolerance. Toon changes must remain confined to the character and
  its intended outline. Record median, p95, and p99 frame time for ON and OFF.
- The reviewer must record PASS or FAIL with evidence for every binary visual
  gate for every ShaderPack. Overall completion requires every gate to PASS on
  all representative packs; averaging scores or allowing one feature to
  compensate for another is forbidden.
- The 2026-08-09 Celerant 1.2.0 Jingburger result is a known failing baseline:
  white materials clip, polygon facets remain visible, outlines are broken or
  speckled, face SDF output does not match the official reference, and rim/hair/
  metal responses are not clearly distinguishable. Do not reuse its prior
  ON/OFF signal result as evidence of visual completion.
