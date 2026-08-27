# XPBD BoneCloth Secondary Physics Design

**Date:** 2026-08-27  
**Status:** Approved (plan XPBD BoneCloth Physics)  
**Mode:** ponytail ultra — Magica BoneCloth *capability*, open XPBD solver; no Magica port, no MeshCloth, no PhysBone.

## Goal

Local-player VRM secondary motion (hair, cloth strips, accessories) with Magica BoneCloth–class Line behavior: distance restore, angle restore, sphere/capsule colliders, gravity, drag, and center-space inertia — via Macklin et al. 2016 XPBD.

## Locked decisions

| Topic | Decision |
|---|---|
| Solver | XPBD (compliance α, λ accumulation, α̃ = α/Δt²) |
| Topology | Magica BoneCloth **Line** only (parent→child chains) |
| Authoring | `VRMC_springBone` + VRM0 `secondaryAnimation` only |
| Hook | After humanoid/loco in `VrmRuntime.submitPosed`, before `model.submit` |
| Scope | Local client avatar only |
| Rejected | MeshCloth, self-collision, Magica assets, PhysBone, remote sync, grab/wind |

## Parameter mapping

| VRM SpringBone | XPBD BoneCloth |
|---|---|
| `stiffness` | stretch compliance α_s = 1 / max(stiffness, ε); bend α_b = 4·α_s |
| `dragForce` | velocity damping on free particles after substep |
| `gravityPower` / `gravityDir` | external force in predict |
| `hitRadius` | particle collision radius |
| collider sphere/capsule | positional contact push-out |
| `center` node | inertia evaluated in center space (Magica world influence) |

## Frame loop

```
rig.apply* / loco
→ restore simulated joints to rest TRS
→ pin kinematic roots to posed parent world positions
→ predict (inertia + gravity)
→ XPBD iterations: stretch, bend, collide
→ write local rotations root→tip onto NodeModel
→ model.submit
→ finally: rig.restore()  // humanoid only; particle state kept
```

## Acceptance

- Springs move under walk/look; config OFF freezes to rest
- Head colliders reduce clipping
- Models with `VRMC_springBone` in `extensionsRequired` load
- No Magica/Unity/PhysBone code; no vertex MeshCloth
