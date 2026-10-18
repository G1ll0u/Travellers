# Camps and block-by-block building

Travellers, raiders and non-hired idle workers occasionally camp away from villages (`EntityAICampStay`). A hired worker, or one on a mission, never camps.

- `CampsiteManager` (WorldSavedData) is a `ChunkReader` consumer: each chunk gets a fixed per-chunk roll (seed + coordinates), so reloads never add campsites. Lifecycle ticked from `TravellerChunkManager`.
- Factions: `CampsiteRecord.canClaim` — travellers and non-hired workers share a civil faction; raiders are exclusive.
- Shelters are vanilla `Template`s built block by block via `camp/build/` (`StructureBuildPlan`, `IncrementalStructureBuilder`), never placed instantly. `CampBuildCoordinator` turns `buildProgress` into blocks identically for loaded, virtual (`advanceVirtual`, bookkeeping only) and reconcile (`syncPhysical`, idempotent) modes.
- Persisted: `buildState` + `buildProgress` + the locked template identity (culture folder + name). Once locked, never repicked.
- Ground fill is computed once, persisted and prepended to the plan — never recomputed from the world (it would shift every plan index).
- Builder never refuses a block forever (yield, then place anyway). Movement distances are XZ-only.
- `CampTerrainValidator` accepts only air or natural, non-liquid, non-hazard cells: failure means "no camp here", never a damaged structure.
- Sleep is a cosmetic loaded-only pose. `campingPassive` suppresses proactive aggression only.
- A virtual record walks to its camp slot (the slot is its goal), never placed on it; it materializes where it is unless already at the slot.
- Death mid-build leaves a ruin (`orphans`). `/travellersdebug camp reset [all]` restores terrain without a player nearby, never on an occupied campsite.
