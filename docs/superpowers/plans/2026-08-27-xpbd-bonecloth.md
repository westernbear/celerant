# XPBD BoneCloth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Magica BoneCloth–class Line secondary physics on local VRM via XPBD, authored from VRMC_springBone / VRM0 secondaryAnimation.

**Architecture:** Parse spring JSON → `BoneClothGraph` particles/constraints → step after retarget in `submitPosed` → write non-humanoid `NodeModel` rotations before MCglTF submit.

**Tech Stack:** Fabric MC 26.2, Java 25, JOML, jgltf, JUnit 5.

**Spec:** [docs/superpowers/specs/2026-08-27-xpbd-bonecloth-design.md](../specs/2026-08-27-xpbd-bonecloth-design.md)

## Global Constraints

- Client-only Fabric mod; no Magica/PhysBone code or assets
- Line BoneCloth only; no MeshCloth / self-collision / remote sync
- Allow `VRMC_springBone` in `SUPPORTED_REQUIRED_EXTENSIONS`
- Config toggle under Motion: `springBoneEnabled` (default true)
- Deterministic unit tests without Minecraft client

## File map

| File | Role |
|------|------|
| `client/physics/SpringBoneCollider.java` | Sphere/capsule signed distance |
| `client/physics/XpbdSolver.java` | Stretch + bend XPBD projections |
| `client/physics/BoneClothGraph.java` | Chain state, predict, step, write-back |
| `client/physics/VrmSpringBoneParser.java` | VRM0/1 JSON → graphs |
| `client/physics/BoneClothSimulator.java` | Multi-graph manager + enable flag |
| `VrmRuntime.java` | Parse, hook, dispose |
| `CelerantConfig.java` | Toggle |
| `src/test/.../physics/*Test.java` | Stretch/bend/collider tests |
| `README.md` | Scope update |

---

### Task 1: Collider + XPBD core (unit-testable)

**Files:**
- Create: `src/client/java/.../client/physics/SpringBoneCollider.java`
- Create: `src/client/java/.../client/physics/XpbdSolver.java`
- Create: `src/test/java/.../client/physics/XpbdSolverTest.java`

- [ ] Implement sphere/capsule `pushOut(position, hitRadius, outNormal) → penetration`
- [ ] Implement `projectStretch` / `projectBend` with λ accumulation
- [ ] Tests: stretch restores rest length; bend under gravity lowers tip; sphere pushes particle out

### Task 2: Graph + parser

**Files:**
- Create: `BoneClothGraph.java`, `VrmSpringBoneParser.java`, `BoneClothSimulator.java`

- [ ] Build Line graph from joint node indices + params
- [ ] Parse VRMC_springBone and VRM0 secondaryAnimation
- [ ] `BoneClothSimulator.step(dt)` / `reset()` / `setEnabled`

### Task 3: Wire runtime + config + README

**Files:**
- Modify: `VrmRuntime.java`, `CelerantConfig.java`, `README.md`

- [ ] Add `VRMC_springBone` to supported required extensions
- [ ] Parse into `ParsedModel`, build simulator in `finishLoad`, clear in `releaseModel`
- [ ] Call `simulator.step(dt)` after loco, before `model.submit`
- [ ] Motion switch `springBoneEnabled`
- [ ] README: remove spring-bone out-of-scope; document toggle

### Task 4: Verify

- [ ] `./gradlew test --tests 'io.github.westernbear.celerant.client.physics.*'`
- [ ] `graphify update .`
