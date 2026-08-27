# Multiplayer VRM + Locomotion + UI Design

**Date:** 2026-08-27  
**Status:** Approved (brainstorming §1–§5)  
**Related research:** [2026-08-27-vrm-avatar-protection-research.md](2026-08-27-vrm-avatar-protection-research.md)

## Goal

Enable multiplayer-visible VRM avatars on Minecraft via a Paper plugin bridge, with Hardened model protection, VRChat-style locomotion synced by parameters, Warudo-style local animation layering, and a dual UI (OneConfig panels + in-game radial) with `en_us` / `ko_kr` parity.

## Locked decisions

| Topic | Decision |
|---|---|
| Transport | Paper/Bukkit plugin (not Fabric server mod); Fabric client uses plugin messaging |
| Security | Hardened: AES-GCM + vertex obfuscation/runtime restore + encrypted disk cache. Not unbreakable DRM |
| Multiplayer pattern | VRChat (upload → store → viewer download + synced params) |
| Local animation | Warudo (Idle + Overlay + Breathing/Swaying) under VRChat Base locomotion |
| Rejected | Warudo Collab/VMC pre-share of plaintext models |
| Locomotion scope | L3: Base FSM + Additive breathing/sway; no Gesture/FX hands in v1 |
| UI | U3: Warudo-like OneConfig + VRChat-like radial |
| i18n | Identical key sets in `en_us.json` and `ko_kr.json` |

## Architecture

```
Fabric Celerant client                    Paper plugin
─────────────────────                     ────────────
OneConfig panels ─┐
Radial menu ──────┼─→ LocoStack L3 ─→ VrmRuntime/VrmRig
                  │         │
                  │         └─→ celerant:loco ──→ LocoBroadcast
                  │
Harden (obfuscate+AES) ─→ chunks/meta/key ←→ EncryptedStore + ACL + SessionKeys
```

### Plugin channels (v1)

| Channel | Purpose |
|---|---|
| `celerant:avatar_meta` | Owner UUID, avatarId, content hash, size, ACL flags |
| `celerant:avatar_chunk` | streamId, index, bytes, final flag |
| `celerant:avatar_key` | Viewer-bound short-lived session key material (ACL re-check) |
| `celerant:loco` | Compact locomotion/expression parameter blob (no mesh) |

Plugin **never** stores plaintext VRM on disk.

## Security (Hardened)

1. Owner client: VRM → vertex obfuscation → AES-GCM envelope → chunk upload.
2. Plugin: ciphertext + metadata + ACL only.
3. Viewer: ACL pass → session key → decrypt → deobfuscate in memory → load; disk cache re-encrypted.
4. Fail closed: hash/key/ACL failure hides avatar (vanilla skin); no plaintext fallback.
5. Residual risk: GPU/memory dump and modified clients can still extract; goal is raising cost like VRChat cache encryption, not perfect DRM.

**Gate:** Phase 4 Hardened code ships only after research doc records **PASS**.

## Locomotion L3

**Inputs from Minecraft entity:** velocity, grounded, crouching, airborne → VRChat-like params (`VelocityX/Y/Z`, `VelocityMagnitude`, `Grounded`, `Crouching`, `InAir`, …).

**Layer stack (bottom → top):**

1. Warudo Idle  
2. Warudo Breathing / Swaying (additive)  
3. Warudo Overlay (masked; built-in presets only in v1)  
4. VRChat Base locomotion blend (idle/walk/run/strafe, crouch, jump/fall)  
5. Fallback: existing `VrmRig.apply(PlayerModel)` if clips missing  

Do **not** bundle copyrighted VRChat proxy animation binaries.

Remote clients replay the same stack from `celerant:loco` params (no bone streaming).

## UI U3

- **Panels:** Extend `CelerantConfig` — Avatar, Motion, Multiplayer, Toon (existing).
- **Radial:** Separate keybinding; expressions, idle preset, avatar visibility, upload/refresh when allowed.
- **i18n:** All user-facing strings via `Component.translatable`; `en_us` / `ko_kr` key parity enforced by test.

## Error handling

| Condition | Behavior |
|---|---|
| Upload fail / size / ACL deny | Toast + `celerant.error.*` |
| Chunk corrupt / hash mismatch | Abort download, delete partial cache, one retry |
| Key expired / ACL fail | Hide remote avatar |
| Harden restore fail | Refuse load; log; no broken mesh |
| Plugin absent | Disable multiplayer; local VRM/loco/UI still work |
| Clips missing | `VrmRig` PlayerModel retarget fallback |

## Work order

0. Research gate → PASS  
1. Local L3 locomotion  
2. UI U3 + en/ko  
3. Paper plugin skeleton + client channels  
4. Hardened upload/key/cache (post PASS)  
5. Remote loco sync  

## Out of scope (v1)

Warudo Blueprint editor, full VRChat Expression SDK, hand Gesture/FX, CDN hybrid, Fabric-only server mod.

## Existing codebase anchors

- `src/client/java/io/github/westernbear/celerant/client/VrmRuntime.java`
- `src/client/java/io/github/westernbear/celerant/client/VrmRig.java`
- `src/client/java/io/github/westernbear/celerant/client/CelerantConfig.java`
- `src/client/java/io/github/westernbear/celerant/client/CelerantClient.java`
- `src/main/resources/assets/celerant/lang/en_us.json`
- `src/main/resources/assets/celerant/lang/ko_kr.json`
