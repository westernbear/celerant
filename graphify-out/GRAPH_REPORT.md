# Graph Report - celerant  (2026-08-08)

## Corpus Check
- 21 files · ~14,200 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 284 nodes · 680 edges · 20 communities (16 shown, 4 thin omitted)
- Extraction: 95% EXTRACTED · 5% INFERRED · 0% AMBIGUOUS · INFERRED: 34 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `3eaaeaa7`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- IrisToonPatcher
- NodeModel
- VrmRuntime
- CelerantClientGameTest
- VrmRuntime.java
- VrmRig
- .register
- Celerant VRM
- LivingEntityRendererMixin.java
- RawBinding
- release.sh
- gradlew
- AvatarRendererMixin.java
- .resolveRenderViews
- AGENTS.md
- Changelog
- .parseRawExpressions

## God Nodes (most connected - your core abstractions)
1. `VrmRuntime` - 81 edges
2. `CelerantClientGameTest` - 36 edges
3. `VrmRig` - 25 edges
4. `Bone` - 23 edges
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

## Communities (20 total, 4 thin omitted)

### Community 0 - "IrisToonPatcher"
Cohesion: 0.14
Nodes (13): ASTParser, Pattern, IrisToonPatcher, Logger, Parameters, PatchShaderType, IrisTransformPatcherMixin, CallbackInfoReturnable (+5 more)

### Community 1 - "NodeModel"
Cohesion: 0.39
Nodes (5): GltfModel, NodeModel, MorphBinding, ParsedModel, VrmExpression

### Community 2 - "VrmRuntime"
Cohesion: 0.18
Nodes (3): RenderedGltfModel, VrmRuntime, Vec3

### Community 3 - "CelerantClientGameTest"
Cohesion: 0.13
Nodes (9): ByteBuffer, CameraType, ClientGameTestContext, FabricClientGameTest, Minecraft, CelerantClientGameTest, Override, TestServerConnection (+1 more)

### Community 4 - "VrmRuntime.java"
Cohesion: 0.14
Nodes (13): Invoker, LevelRenderContext, AvatarRendererAccessor, AvatarRenderState, Mixin, PoseStack, AvatarRenderState, PlayerModel (+5 more)

### Community 5 - "VrmRig"
Cohesion: 0.10
Nodes (8): ModelPart, Quaternionf, Bone, AvatarRenderState, GltfModel, NodeModel, PlayerModel, VrmRig

### Community 7 - ".register"
Cohesion: 0.15
Nodes (5): ClientModInitializer, FabricClientCommandSource, CelerantClient, Override, VrmClientCommands

### Community 8 - "Celerant VRM"
Cohesion: 0.29
Nodes (6): Celerant VRM, CI와 릴리스, ShaderPack 경계, 사용, 요구 모드, 테스트

### Community 9 - "LivingEntityRendererMixin.java"
Cohesion: 0.29
Nodes (10): CameraRenderState, LivingEntityRenderState, RenderType, CallbackInfo, CallbackInfoReturnable, Inject, Mixin, PoseStack (+2 more)

### Community 10 - "RawBinding"
Cohesion: 0.67
Nodes (3): RawBinding, RawExpression, RawExpressions

### Community 12 - "gradlew"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

### Community 13 - "AvatarRendererMixin.java"
Cohesion: 0.30
Nodes (9): AvatarRendererMixin, AvatarRenderState, CallbackInfo, CallbackInfoReturnable, Identifier, Inject, Mixin, PoseStack (+1 more)

### Community 14 - ".resolveRenderViews"
Cohesion: 0.15
Nodes (10): IdentityHashMap, RenderView, FirstPersonAnchor, RawFirstPerson, RenderViews, ViewType, AUTO, BOTH (+2 more)

## Knowledge Gaps
- **12 isolated node(s):** `release.sh script`, `AUTO`, `BOTH`, `FIRST_PERSON_ONLY`, `THIRD_PERSON_ONLY` (+7 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **4 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `VrmRuntime` connect `VrmRuntime` to `NodeModel`, `CelerantClientGameTest`, `VrmRuntime.java`, `VrmRig`, `.selfCheck`, `.register`, `LivingEntityRendererMixin.java`, `RawBinding`, `AvatarRendererMixin.java`, `.resolveRenderViews`, `.parseRawExpressions`?**
  _High betweenness centrality (0.485) - this node is a cross-community bridge._
- **Why does `VrmRig` connect `VrmRig` to `VrmRuntime`, `VrmRuntime.java`, `.register`?**
  _High betweenness centrality (0.185) - this node is a cross-community bridge._
- **What connects `release.sh script`, `AUTO`, `BOTH` to the rest of the system?**
  _12 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `IrisToonPatcher` be split into smaller, more focused modules?**
  _Cohesion score 0.14245014245014245 - nodes in this community are weakly interconnected._
- **Should `CelerantClientGameTest` be split into smaller, more focused modules?**
  _Cohesion score 0.13333333333333333 - nodes in this community are weakly interconnected._
- **Should `VrmRuntime.java` be split into smaller, more focused modules?**
  _Cohesion score 0.13538461538461538 - nodes in this community are weakly interconnected._
- **Should `VrmRig` be split into smaller, more focused modules?**
  _Cohesion score 0.10195035460992907 - nodes in this community are weakly interconnected._