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

## Genshin toon acceptance criteria

- VRM toon work must produce a recognizably Genshin-style result, using `kaze-mio/UnityGenshinToonShader` and its reference images as the visual baseline. A generic Iris ShaderPack look, simple MToon darkening, or a two-band color ramp alone is not complete.
- The target includes smooth character shading without visible low-poly normal facets, material-aware shadow ramps, intentional face shading, rim lighting, and character outlines. If required model data such as a LightMap, face SDF, smooth normals, or outline controls is absent, add an explicit compatible data path or report the feature as incomplete; do not silently substitute a generic effect.
- Completion requires Jingburger VRM screenshots rendered with a real current Modrinth Iris ShaderPack enabled, with toon ON/OFF/restored comparisons. Inspect the images directly and do not claim success unless the ON image is materially closer to the Genshin reference while the ShaderPack remains active.
- Preserve unknown ShaderPack G-buffer semantics and source files. Unsupported attachment contracts must remain fail-closed, and visual gains must be measured alongside frame-time impact.
- Prioritize general Iris, GLSL, VRM, and MToon contracts over ShaderPack-specific behavior. Detect output locations, types, and material inputs from the transformed program; do not tune colors, thresholds, light directions, or shader behavior by pack name or archive hash.
- A pack-specific codec is a last resort permitted only for a genuinely unique, documented storage format whose non-color bits are preserved exactly. Keep the Genshin material model shared, keep unknown formats fail-closed, and validate compatibility changes across representative packs so a single-pack screenshot cannot define the implementation.
