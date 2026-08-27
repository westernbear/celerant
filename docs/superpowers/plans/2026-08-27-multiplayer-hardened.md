# Research Gate + Paper Plugin + Hardened Multiplayer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Document avatar-protection research to PASS, ship a Paper plugin that relays encrypted avatar chunks and loco params, then implement Hardened client crypto and remote avatar rendering with loco sync.

**Architecture:** Paper plugin owns ciphertext store, ACL, session keys, and `celerant:loco` fan-out. Fabric client registers plugin channels, uploads/downloads envelopes, deobfuscates only in memory, and applies remote loco params to the same L3 stack.

**Tech Stack:** Paper API (documented version alignment), Fabric networking plugin messages, AES-GCM (JDK `javax.crypto`), SHA-256 content hash

**Spec:** `docs/superpowers/specs/2026-08-27-multiplayer-vrm-loco-design.md`  
**Research:** `docs/superpowers/specs/2026-08-27-vrm-avatar-protection-research.md`

## Global Constraints

- No Hardened implementation before research doc records **PASS**
- Plugin never stores plaintext VRM
- Fail closed on hash/key/ACL errors; no plaintext fallback
- Channels: `celerant:avatar_meta`, `celerant:avatar_chunk`, `celerant:avatar_key`, `celerant:loco`
- Graceful degrade when plugin absent (local features keep working)
- Respect AGENTS.md Minecraft memory limits for any live tests

## File map

| File | Responsibility |
|---|---|
| `docs/superpowers/specs/2026-08-27-vrm-avatar-protection-research.md` | Research + PASS |
| `celerant-paper/` | Paper plugin module |
| `src/client/java/.../net/CelerantChannels.java` | Channel IDs + codecs |
| `src/client/java/.../net/CelerantClientNet.java` | Register/send/receive |
| `src/client/java/.../secure/VertexObfuscator.java` | Mesh scramble/restore |
| `src/client/java/.../secure/AvatarEnvelope.java` | AES-GCM wrap/unwrap |
| `src/client/java/.../secure/EncryptedAvatarCache.java` | Disk cache |
| `src/client/java/.../remote/RemoteAvatarManager.java` | Per-player remote models |
| `src/test/java/.../secure/*Test.java` | Crypto round-trips |

---

### Task 1: Research document PASS

**Files:**
- Create: `docs/superpowers/specs/2026-08-27-vrm-avatar-protection-research.md`

- [ ] **Step 1: Document** VRChat CDN/cache encryption + residual rip risk; Warudo Collab/VMC pre-share; AvaCrypt vertex keys; academic GLB vertex prep
- [ ] **Step 2: Prescribe** Celerant Hardened recipe + explicit non-goals
- [ ] **Step 3: Record verdict `PASS`** with date
- [ ] **Step 4: Commit** `docs: avatar protection research PASS`

---

### Task 2: Channel constants + client net stub

**Files:**
- Create: `src/client/java/.../net/CelerantChannels.java`
- Create: `src/client/java/.../net/CelerantClientNet.java`
- Modify: `CelerantClient.java` — init net; no-op if server lacks plugin

- [ ] **Step 1: Define Identifier channels**
- [ ] **Step 2: Register receivers; track `pluginPresent`**
- [ ] **Step 3: Commit** `feat(net): plugin messaging channel stubs`

---

### Task 3: Paper plugin skeleton

**Files:**
- Create: `celerant-paper/build.gradle.kts` (or Gradle Groovy), `plugin.yml`, main plugin class
- Create: store, ACL, chunk relay, loco broadcast handlers

- [ ] **Step 1: Scaffold Paper project** targeting a documented Paper version; note Fabric 26.2 alignment risk in README
- [ ] **Step 2: Implement ciphertext store + meta/chunk/key/loco handlers**
- [ ] **Step 3: Commit** `feat(paper): celerant avatar relay plugin skeleton`

---

### Task 4: Vertex obfuscator + AES envelope (post PASS)

**Files:**
- Create: `secure/VertexObfuscator.java`, `secure/AvatarEnvelope.java`
- Test: round-trip tests on synthetic float arrays / byte payloads

- [ ] **Step 1: Failing tests** for obfuscate↔restore and AES-GCM wrap↔unwrap
- [ ] **Step 2: Implement**
- [ ] **Step 3: PASS; commit** `feat(secure): Hardened obfuscation and AES envelope`

---

### Task 5: Upload / download / encrypted cache

**Files:**
- Create: `EncryptedAvatarCache.java`, wire upload from config Multiplayer button
- Modify: `RemoteAvatarManager` load path

- [ ] **Step 1: Owner upload path** through channels
- [ ] **Step 2: Viewer download + session key + cache**
- [ ] **Step 3: Fail-closed behaviors; commit** `feat(secure): avatar upload download cache`

---

### Task 6: Remote render + loco sync

**Files:**
- Create: `remote/RemoteAvatarManager.java`
- Modify: render mixins / `VrmRuntime` to submit remote players
- Modify: send/receive `celerant:loco`

- [ ] **Step 1: Map UUID → loaded remote model**
- [ ] **Step 2: Apply remote `LocoParams` via `VrmLocomotion`**
- [ ] **Step 3: Commit** `feat(multi): remote avatars and loco sync`

---
