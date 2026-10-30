# Millénaire integration and village visits

**`village/` is the only package that reads Millénaire data** (plus combat guards and mixins). Settlement, building and villager records are resident from world load and never need loaded chunks — chest contents excepted.

- `MillSettlements`: the one settlement registry (villages + lone buildings), identified by **XZ centre only**. Never read `villagesList`/`getBuilding(Point)` elsewhere.
- `MillGoods`: the one place items move into a settlement's stores; it borrows chest chunks itself (`loadTemporarily`/`release`); offscreen callers take `LoadedChunks.tryAcquireJob` first. Deposit the exact stack (NBT included).
- `MillFootprints`: "is this spot inside a building" from resident data (boxes recomputed like `BuildingPlan`). Every placement path rejects `isForbiddenLanding`; spawns use `pushOutside`.
- `MillRelations.getRelation` returning `null` = neutral.
- Village gates come from `buildingProjects[WALLBUILDING]` resident data (`VillageGateIndex`), never a block scan.

## Village visits
A village visit is a stay (`stay/CLAUDE.md`) whose place is a settlement: its buildings and panels come from `VillageVisitIndex` (resident data, cached), stroll spots from `village/visit/VillageGround` (always outdoors). Indoor spots only when a real A* path ends on them, entered and left through the entrance. Targets drawn per building, weighted by `StayOccupancy`. A worker donates (`donateToConstruction`) when it leaves. Rationale: memory `project_village_visit`.

Debug: `/travellersdebug village needs|show|hide|here`.
