# Millénaire integration and village visits

**`village/` is the only package that reads Millénaire data** (plus combat guards and mixins). Settlement, building and villager records are resident from world load and never need loaded chunks — chest contents excepted.

- `MillSettlements`: the one settlement registry (villages + lone buildings), identified by **XZ centre only**. Never read `villagesList`/`getBuilding(Point)` elsewhere.
- `MillGoods`: the one place items move into a settlement's stores; it borrows chest chunks itself (`loadTemporarily`/`release`); offscreen callers take `LoadedChunks.tryAcquireJob` first. Deposit the exact stack (NBT included).
- `MillFootprints`: "is this spot inside a building" from resident data (boxes recomputed like `BuildingPlan`). Every placement path rejects `isForbiddenLanding`; spawns use `pushOutside`.
- `MillRelations.getRelation` returning `null` = neutral.
- Village gates come from `buildingProjects[WALLBUILDING]` resident data (`VillageGateIndex`), never a block scan.

## Village visits (`village/visit/`)
Travellers, raiders (mirrored, streets only by default) and non-hired workers. Rationale: memory `project_village_visit`.
- **A stop is left only once its visit marks it done** (`TravelCommon.visitedStop`); arrival = arrival radius or inside the town-hall footprint.
- The stay is a `VillageVisitState` (persisted, on entity and record, moved at hand-off); the task holds only walking/gaze/talk state, so a fight, restart or virtualization resumes the same stay.
- Targets from `VillageVisitIndex` (resident data, cached); stroll spots from `VillageGround` (always outdoors). Indoor spots only when a real A* path ends on them, entered and left through the entrance. Targets drawn per building, weighted by `VisitOccupancy`.
- Head only through `TravellerGaze` foci. Talks are `VillageConversation` (loaded-only).
- Leaving is a sequence: out of buildings → back to the hitched horse → a leader waits for its members → `visitedStop`. A worker donates (`donateToConstruction`) then.
- Group followers disperse while their leader stays (`isDispersedInVillage`); their stay ends when the leader leaves.
- Offscreen: `VirtualTravellerManager.tickVirtualVisit`, same draw and sequence.

Debug: `/travellersdebug village needs|show|hide|here`.
