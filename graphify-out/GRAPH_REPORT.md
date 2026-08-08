# Graph Report - celerant  (2026-08-08)

## Corpus Check
- 28 files · ~17,821 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 368 nodes · 912 edges · 19 communities (16 shown, 3 thin omitted)
- Extraction: 95% EXTRACTED · 5% INFERRED · 0% AMBIGUOUS · INFERRED: 46 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `7e8e4fbf`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- IrisToonPatcher
- CelerantClient
- VrmRuntime
- CelerantClientGameTest
- VrmRuntime.java
- VrmRig
- InputConstantsGameTestMixin.java
- .getInstance
- Celerant VRM
- LivingEntityRendererMixin.java
- TinyFdGameTestMixin.java
- release.sh
- gradlew
- AvatarRendererMixin.java
- PresentedWindowCapture
- AGENTS.md
- Changelog

## God Nodes (most connected - your core abstractions)
1. `VrmRuntime` - 86 edges
2. `CelerantClientGameTest` - 71 edges
3. `VrmRig` - 25 edges
4. `Bone` - 23 edges
5. `IrisToonPatcher` - 18 edges
6. `CelerantConfig` - 14 edges
7. `UiBounds` - 9 edges
8. `ParsedModel` - 8 edges
9. `ViewType` - 7 edges
10. `VrmClientCommands` - 6 edges

## Surprising Connections (you probably didn't know these)
- `VrmRuntime` --references--> `VrmRig`  [EXTRACTED]
  src/client/java/io/github/westernbear/celerant/client/VrmRuntime.java → src/client/java/io/github/westernbear/celerant/client/VrmRig.java

## Import Cycles
- None detected.

## Communities (19 total, 3 thin omitted)

### Community 0 - "IrisToonPatcher"
Cohesion: 0.14
Nodes (13): ASTParser, Pattern, IrisToonPatcher, Logger, Parameters, PatchShaderType, IrisTransformPatcherMixin, CallbackInfoReturnable (+5 more)

### Community 1 - "CelerantClient"
Cohesion: 0.70
Nodes (3): ClientModInitializer, KeyMapping, CelerantClient

### Community 2 - "VrmRuntime"
Cohesion: 0.07
Nodes (23): IdentityHashMap, JsonArray, JsonObject, RenderedGltfModel, RenderView, FirstPersonAnchor, GltfModel, NodeModel (+15 more)

### Community 3 - "CelerantClientGameTest"
Cohesion: 0.08
Nodes (14): BufferedImage, ByteBuffer, CameraType, ClientGameTestContext, FabricClientGameTest, NotificationType, CelerantClientGameTest, FileDialogRequest (+6 more)

### Community 4 - "VrmRuntime.java"
Cohesion: 0.13
Nodes (14): Invoker, LevelRenderContext, AvatarRendererAccessor, AvatarRenderState, Mixin, PoseStack, AvatarRenderState, Minecraft (+6 more)

### Community 5 - "VrmRig"
Cohesion: 0.10
Nodes (8): ModelPart, Quaternionf, Bone, AvatarRenderState, GltfModel, NodeModel, PlayerModel, VrmRig

### Community 6 - "InputConstantsGameTestMixin.java"
Cohesion: 0.43
Nodes (5): Key, InputConstantsGameTestMixin, CallbackInfoReturnable, Inject, Mixin

### Community 7 - ".getInstance"
Cohesion: 0.10
Nodes (6): Button, Config, FabricClientCommandSource, Override, CelerantConfig, VrmClientCommands

### Community 8 - "Celerant VRM"
Cohesion: 0.29
Nodes (6): Celerant VRM, CI와 릴리스, ShaderPack 경계, 사용, 요구 모드, 테스트

### Community 9 - "LivingEntityRendererMixin.java"
Cohesion: 0.29
Nodes (10): CameraRenderState, LivingEntityRenderState, RenderType, CallbackInfo, CallbackInfoReturnable, Inject, Mixin, PoseStack (+2 more)

### Community 10 - "TinyFdGameTestMixin.java"
Cohesion: 0.53
Nodes (4): CallbackInfoReturnable, Inject, Mixin, TinyFdGameTestMixin

### Community 12 - "gradlew"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

### Community 13 - "AvatarRendererMixin.java"
Cohesion: 0.30
Nodes (9): AvatarRendererMixin, AvatarRenderState, CallbackInfo, CallbackInfoReturnable, Identifier, Inject, Mixin, PoseStack (+1 more)

### Community 16 - "Changelog"
Cohesion: 0.50
Nodes (3): 1.1.1, Changelog, Unreleased

## Knowledge Gaps
- **13 isolated node(s):** `release.sh script`, `AUTO`, `BOTH`, `FIRST_PERSON_ONLY`, `THIRD_PERSON_ONLY` (+8 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **3 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `VrmRuntime` connect `VrmRuntime` to `VrmRuntime.java`, `VrmRig`, `.getInstance`, `LivingEntityRendererMixin.java`, `AvatarRendererMixin.java`?**
  _High betweenness centrality (0.487) - this node is a cross-community bridge._
- **Why does `VrmRig` connect `VrmRig` to `VrmRuntime`, `VrmRuntime.java`, `.getInstance`?**
  _High betweenness centrality (0.165) - this node is a cross-community bridge._
- **Why does `CelerantClientGameTest` connect `CelerantClientGameTest` to `TinyFdGameTestMixin.java`?**
  _High betweenness centrality (0.157) - this node is a cross-community bridge._
- **What connects `release.sh script`, `AUTO`, `BOTH` to the rest of the system?**
  _13 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `IrisToonPatcher` be split into smaller, more focused modules?**
  _Cohesion score 0.14245014245014245 - nodes in this community are weakly interconnected._
- **Should `VrmRuntime` be split into smaller, more focused modules?**
  _Cohesion score 0.07333333333333333 - nodes in this community are weakly interconnected._
- **Should `CelerantClientGameTest` be split into smaller, more focused modules?**
  _Cohesion score 0.07687894434882386 - nodes in this community are weakly interconnected._