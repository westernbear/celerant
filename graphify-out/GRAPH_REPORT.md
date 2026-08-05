# Graph Report - celerant  (2026-08-05)

## Corpus Check
- 16 files · ~7,807 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 149 nodes · 323 edges · 17 communities (12 shown, 5 thin omitted)
- Extraction: 99% EXTRACTED · 1% INFERRED · 0% AMBIGUOUS · INFERRED: 4 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- IrisToonPatcher
- VrmRuntime
- .finishLoad
- CelerantClientGameTest
- VrmRuntime.java
- IrisTransformPatcherMixin.java
- NodeModel
- .register
- Celerant VRM
- .onInitializeClient
- .readModel
- release.sh
- gradlew
- AGENTS.md
- .parseRawExpressions

## God Nodes (most connected - your core abstractions)
1. `VrmRuntime` - 56 edges
2. `CelerantClientGameTest` - 18 edges
3. `IrisToonPatcher` - 15 edges
4. `VrmClientCommands` - 6 edges
5. `RawBinding` - 6 edges
6. `Celerant VRM` - 6 edges
7. `ParsedModel` - 5 edges
8. `VrmExpression` - 5 edges
9. `RawExpression` - 5 edges
10. `Celerant` - 5 edges

## Surprising Connections (you probably didn't know these)
- `VrmRuntime` --references--> `IdentityHashMap`  [EXTRACTED]
  src/client/java/io/github/westernbear/celerant/client/VrmRuntime.java →   _Bridges community 1 → community 2_
- `VrmRuntime` --references--> `NodeModel`  [EXTRACTED]
  src/client/java/io/github/westernbear/celerant/client/VrmRuntime.java →   _Bridges community 1 → community 6_
- `ParsedModel` --references--> `VrmExpression`  [EXTRACTED]
  src/client/java/io/github/westernbear/celerant/client/VrmRuntime.java → src/client/java/io/github/westernbear/celerant/client/VrmRuntime.java  _Bridges community 10 → community 6_
- `RawExpressions` --references--> `RawExpression`  [EXTRACTED]
  src/client/java/io/github/westernbear/celerant/client/VrmRuntime.java → src/client/java/io/github/westernbear/celerant/client/VrmRuntime.java  _Bridges community 6 → community 19_

## Import Cycles
- None detected.

## Communities (17 total, 5 thin omitted)

### Community 0 - "IrisToonPatcher"
Cohesion: 0.20
Nodes (7): ASTParser, Pattern, IrisToonPatcher, Logger, Parameters, PatchShaderType, TranslationUnit

### Community 2 - ".finishLoad"
Cohesion: 0.22
Nodes (3): IdentityHashMap, RenderedGltfModel, Vec3

### Community 3 - "CelerantClientGameTest"
Cohesion: 0.21
Nodes (7): ClientGameTestContext, FabricClientGameTest, Minecraft, CelerantClientGameTest, Override, TestServerConnection, TestSingleplayerContext

### Community 4 - "VrmRuntime.java"
Cohesion: 0.27
Nodes (5): ByteBuffer, Identifier, LevelRenderContext, Celerant, Logger

### Community 5 - "IrisTransformPatcherMixin.java"
Cohesion: 0.43
Nodes (6): CallbackInfoReturnable, Inject, Mixin, IrisTransformPatcherMixin, Parameters, PatchShaderType

### Community 6 - "NodeModel"
Cohesion: 0.43
Nodes (4): NodeModel, MorphBinding, RawExpressions, VrmExpression

### Community 8 - "Celerant VRM"
Cohesion: 0.29
Nodes (6): Celerant VRM, CI와 릴리스, ShaderPack 경계, 사용, 요구 모드, 테스트

### Community 9 - ".onInitializeClient"
Cohesion: 0.33
Nodes (3): ClientModInitializer, CelerantClient, Override

### Community 12 - "gradlew"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

### Community 19 - ".parseRawExpressions"
Cohesion: 0.31
Nodes (4): JsonArray, JsonObject, RawBinding, RawExpression

## Knowledge Gaps
- **7 isolated node(s):** `release.sh script`, `graphify`, `요구 모드`, `사용`, `ShaderPack 경계` (+2 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **5 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `VrmRuntime` connect `VrmRuntime` to `.finishLoad`, `CelerantClientGameTest`, `VrmRuntime.java`, `NodeModel`, `.register`, `.onInitializeClient`, `.readModel`, `.parseRawExpressions`?**
  _High betweenness centrality (0.339) - this node is a cross-community bridge._
- **What connects `release.sh script`, `graphify`, `요구 모드` to the rest of the system?**
  _7 weakly-connected nodes found - possible documentation gaps or missing edges._