# VRM Avatar Protection Research

**Date:** 2026-08-27  
**Verdict:** **PASS**  
**Feeds:** Hardened multiplayer implementation (design Phase 4)

## Scope

Evaluate how VRChat and Warudo handle avatar distribution and anti-theft, plus academic/community techniques applicable to Celerant (Fabric client + Paper plugin).

## VRChat

### Distribution

- Creators upload avatars to VRChat backend; other clients download AssetBundles (historically via CDN/API) when they need to render a remote player.
- Runtime sync for appearance/behavior uses **synced Expression Parameters** and built-in locomotion/IK params (budget ~256 bits for custom synced params)—not continuous full-mesh retransmission.
- Late joiners re-download or use cache; remote clients re-evaluate animators from synced params.

### Protection

- Client-side **cache encryption** (AES-family) so copying `__data` cache files alone is insufficient.
- Keys tied to session/machine/runtime memory; Easy Anti-Cheat raises the cost of memory dumps and client mods.
- Community reports (ripper forums, decryptor tools) show bypasses still exist when keys can be extracted—protection is **deterrence**, not cryptographic impossibility.
- Related industry direction: encrypted asset chunks with cache deduplication (patent literature around encrypted asset bundles).

### Takeaway for Celerant

Adopt upload → server-held ciphertext → viewer session keys → encrypted local cache → **parameter sync for motion**, not bone streams.

## Warudo

### Distribution / collab

- Primarily a **local VTubing** app (VRM / `.warudo` on disk).
- **Steam Collab** and **Multiplayer VMC** sync bone rotations, blendshapes, mesh visibility—or VMC tracking only.
- Explicit model policy: partners must **already possess the same model file**; tools do not distribute VRM bytes. This conflicts with “don’t let others take my model.”

### Animation (useful; not for multi protect)

- Idle + Overlay (masks/weights) + Transient one-shots + Breathing/Swaying.
- No built-in VRChat-equivalent locomotion FSM; community blueprints add walk/jog/run.

### Takeaway for Celerant

Use Warudo patterns for **local layering only**. Reject Collab/VMC as the multiplayer model-distribution strategy.

## Community Hardened techniques

- **AvaCrypt / GTAnti-Rip (VRChat):** Destructive vertex randomization written to mesh; custom shader or runtime uses per-avatar keys to un-scramble. Raises rip cost; keys in shared sessions remain attackable; authors state nothing is 100% secure.
- **VRoid SDK:** Optional `.enc.vrm` download encryption for authorized SDK consumers—platform-controlled keying, not a general Minecraft solution.

## Academic / literature signals

- Metaverse GLB work focuses on **extracting vertex arrays** as an encryption-ready representation, then applying standard ciphers (AES, etc.) rather than inventing a new algorithm—useful framing for “obfuscate geometry, then envelope-encrypt the payload.”
- Face/avatar copyright papers (e.g. generation-time defenses) are largely orthogonal to runtime multiplayer mesh delivery.

## Residual risks (must remain documented in product)

1. Any client that can draw the mesh holds decrypted geometry in GPU/CPU memory.
2. Modified clients can skip cache encryption or dump keys.
3. Session key distribution to legitimate viewers is a necessary exposure.
4. Perfect DRM is out of scope; success metric is “casual export and cache theft fail; determined attackers need non-trivial reverse engineering.”

## Celerant Hardened recipe (approved)

1. **Vertex obfuscation** of mesh positions (deterministic scramble with secret key material) before network/disk.
2. **AES-GCM** envelope over the obfuscated payload; content **SHA-256** for integrity.
3. Paper plugin stores **ciphertext only**; ACL gates who may receive chunks and **short-lived session keys**.
4. Viewer decrypts → deobfuscates **in memory** → loads into `VrmRuntime`; disk cache stored re-encrypted.
5. Motion: sync `LocoParams` (and later expression weights), never raw bones.
6. Fail closed on hash/ACL/key failure; never fall back to plaintext VRM on shared paths.

## Verdict

**PASS** — Evidence is sufficient to implement Hardened multiplayer as specified. Limits are acknowledged; Warudo pre-share is rejected; VRChat-style distribution + param sync + cache/envelope encryption + vertex obfuscation is the chosen stack.

*Signed off: 2026-08-27 (design approval + literature/web survey).*
