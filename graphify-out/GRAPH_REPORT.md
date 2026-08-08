# Graph Report - celerant  (2026-08-08)

## Corpus Check
- 20 files · ~12,380 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 264 nodes · 625 edges · 20 communities (17 shown, 3 thin omitted)
- Extraction: 96% EXTRACTED · 4% INFERRED · 0% AMBIGUOUS · INFERRED: 25 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `9d0fcf3c`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- IrisToonPatcher
- .resolveRenderViews
- VrmRuntime
- CelerantClientGameTest
- VrmRuntime.java
- VrmRig
- NodeModel
- .register
- Celerant VRM
- LivingEntityRendererMixin.java
- release.sh
- gradlew
- AvatarRendererMixin.java
- ViewType
- AGENTS.md
- RawBinding
- .parseRawExpressions

## God Nodes (most connected - your core abstractions)
1. `VrmRuntime` - 81 edges
2. `CelerantClientGameTest` - 29 edges
3. `VrmRig` - 21 edges
4. `Bone` - 17 edges
5. `IrisToonPatcher` - 15 edges
6. `ViewType` - 7 edges
7. `VrmClientCommands` - 6 edges
8. `ParsedModel` - 6 edges
9. `FirstPersonAnchor` - 6 edges
10. `RawBinding` - 6 edges

## Surprising Connections (you probably didn't know these)
- `VrmRuntime` --references--> `VrmRig`  [EXTRACTED]
  src/client/java/io/github/westernbear/celerant/client/VrmRuntime.java → src/client/java/io/github/westernbear/celerant/client/VrmRig.java

## Import Cycles
- None detected.

## Communities (20 total, 3 thin omitted)

### Community 0 - "IrisToonPatcher"
Cohesion: 0.14
Nodes (13): ASTParser, Pattern, IrisToonPatcher, Logger, Parameters, PatchShaderType, IrisTransformPatcherMixin, CallbackInfoReturnable (+5 more)

### Community 1 - ".resolveRenderViews"
Cohesion: 0.23
Nodes (5): IdentityHashMap, RenderView, FirstPersonAnchor, RawFirstPerson, RenderViews

### Community 2 - "VrmRuntime"
Cohesion: 0.21
Nodes (3): RenderedGltfModel, VrmRuntime, Vec3

### Community 3 - "CelerantClientGameTest"
Cohesion: 0.16
Nodes (8): CameraType, ClientGameTestContext, FabricClientGameTest, Minecraft, CelerantClientGameTest, Override, TestServerConnection, TestSingleplayerContext

### Community 4 - "VrmRuntime.java"
Cohesion: 0.14
Nodes (14): ByteBuffer, Invoker, LevelRenderContext, AvatarRendererAccessor, AvatarRenderState, Mixin, PoseStack, AvatarRenderState (+6 more)

### Community 5 - "VrmRig"
Cohesion: 0.12
Nodes (8): ModelPart, Quaternionf, Bone, AvatarRenderState, GltfModel, NodeModel, PlayerModel, VrmRig

### Community 6 - "NodeModel"
Cohesion: 0.39
Nodes (5): GltfModel, NodeModel, MorphBinding, ParsedModel, VrmExpression

### Community 7 - ".register"
Cohesion: 0.15
Nodes (5): ClientModInitializer, FabricClientCommandSource, CelerantClient, Override, VrmClientCommands

### Community 8 - "Celerant VRM"
Cohesion: 0.29
Nodes (6): Celerant VRM, CI와 릴리스, ShaderPack 경계, 사용, 요구 모드, 테스트

### Community 9 - "LivingEntityRendererMixin.java"
Cohesion: 0.29
Nodes (10): CameraRenderState, LivingEntityRenderState, RenderType, CallbackInfo, CallbackInfoReturnable, Inject, Mixin, PoseStack (+2 more)

### Community 12 - "gradlew"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

### Community 13 - "AvatarRendererMixin.java"
Cohesion: 0.30
Nodes (9): AvatarRendererMixin, AvatarRenderState, CallbackInfo, CallbackInfoReturnable, Identifier, Inject, Mixin, PoseStack (+1 more)

### Community 14 - "ViewType"
Cohesion: 0.40
Nodes (5): ViewType, AUTO, BOTH, FIRST_PERSON_ONLY, THIRD_PERSON_ONLY

### Community 16 - "RawBinding"
Cohesion: 0.67
Nodes (3): RawBinding, RawExpression, RawExpressions

## Knowledge Gaps
- **11 isolated node(s):** `release.sh script`, `AUTO`, `BOTH`, `FIRST_PERSON_ONLY`, `THIRD_PERSON_ONLY` (+6 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **3 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `VrmRuntime` connect `VrmRuntime` to `.resolveRenderViews`, `CelerantClientGameTest`, `VrmRuntime.java`, `VrmRig`, `NodeModel`, `.register`, `LivingEntityRendererMixin.java`, `.selfCheck`, `AvatarRendererMixin.java`, `ViewType`, `RawBinding`, `.parseRawExpressions`?**
  _High betweenness centrality (0.491) - this node is a cross-community bridge._
- **Why does `VrmRig` connect `VrmRig` to `VrmRuntime`, `.register`?**
  _High betweenness centrality (0.165) - this node is a cross-community bridge._
- **What connects `release.sh script`, `AUTO`, `BOTH` to the rest of the system?**
  _11 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `IrisToonPatcher` be split into smaller, more focused modules?**
  _Cohesion score 0.14245014245014245 - nodes in this community are weakly interconnected._
- **Should `VrmRuntime.java` be split into smaller, more focused modules?**
  _Cohesion score 0.13538461538461538 - nodes in this community are weakly interconnected._
- **Should `VrmRig` be split into smaller, more focused modules?**
  _Cohesion score 0.11605937921727395 - nodes in this community are weakly interconnected._