**Status: steps 1–3b done; 3c–5 built, not checked in game.**

# Rest spots

## Goal

Pretty, roomy places in the wild (riverbank, summit, open view, forest edge) where travellers, raiders and free henchmen stop for a break. v1: find and score the spots, show them on the population map, and let groups stop there for a while, loaded and virtual alike. Sitting, picnic, firepit, fishing and singing at the spot come later.

## Decisions taken with the user

| Topic | Decision |
|---|---|
| Relation to camps | Separate, lightweight system (no building). Reuses shared helpers, not `CampsiteManager`. |
| Who | Travellers, raiders, henchmen **not hired** only. A hired worker never goes on a break. |
| How a unit goes there | When a trip is planned, a rest spot near the route may be added as an extra stop (chance roll), like the village stops collected along the corridor. |
| Factions | One faction at a time, like camps: civil (travellers + unhired workers) or raiders. |
| Time of day | Any time. |
| Name | "Rest spots" (config category `restSpots`). |
| Display | Population map (`/travellersmap`) only, reusing an existing marker shape with its own colour; tooltip shows the score and its reasons. |
| Config | Every tuning knob a player or admin might touch is a config field (see below). |
| Trips | One shared trip rule for loaded and virtual units (`travel/TripPlanner`): offscreen units now also stop at villages on the way. |
| Pause code | The village visit was already place-neutral except a few points: it becomes one stay task (`stay/`) with a pluggable place (`StayPlace`: village or rest spot), loaded and offscreen. Renamed from "village visit" to "stay" in code (saves stay compatible). |
| Horses | Dismount; the horse waits tied at the edge of the spot, like at a village. |
| Groups | Members disperse in the spot, like in a village. |

## How a spot is found

- A new consumer in `ChunkReader.readNow`, never a second queue. A fixed per-chunk roll (seed + coordinates, like campsites) picks which chunks are candidates, so exploring again never adds spots.
- Cheap check on read (centre column only): ground via `ColumnGround`/`Footing`, not near a village (`MillSettlements`), not in a building (`MillFootprints`), spacing from other spots.
- Full scoring is lazy, once the 3x3 chunks around the candidate are loaded (the scenery crosses chunk borders), under a per-tick budget. Never loads a chunk.
- **Room**: enough walkable cells (slope allowed, within a step height between neighbours) in one connected patch around the centre. Too small = dropped.
- **Beauty score** = weighted sum, each weight a config field:
  - water: river or lake edge within a few blocks of the walkable patch (not inside water, not open ocean);
  - height: the chunk stands above its neighbours in the terrain grid (summit, ridge, cliff over a valley);
  - open sky over the patch, with some trees around (forest edge, not deep forest);
  - flowers and tall grass on the patch;
  - biome bonus/penalty list (e.g. swamp, desert lower).
- Spots under the minimum score are dropped. The record keeps the score parts for the tooltip.

## How a unit uses it

- `TravelCommon` trip planning: after picking the destination and village stops, look for the best spot within the corridor; with a chance, insert it as a stop. Trip stops then need a kind (village / rest spot): every code path that treats a stop as a village (visits, arrival in a town hall, recent destinations) must skip rest spots. **Main risk of the plan.**
- At the stop: a persisted pause (spot id + end time) on the entity **and** `VirtualTravellerRecord`, captured/restored by `VirtualSnapshot`, reset at materialize. Loaded: units stroll and idle within the patch. Virtual: the record waits at its spot until the end time, then the trip continues.
- A group pauses together (leader decides, followers idle around). A spot already held by the other faction is skipped at planning and re-checked on arrival.
- Combat interrupts as usual; the pause resumes afterwards.

## Config (`TravellersConfig.RestSpots`, defaults to confirm)

| Field | Default | Effect |
|---|---|---|
| `enabled` | true | Master switch. |
| `discoveryChancePerChunk` | 0.10 | How often a patch of land is checked for a rest spot. |
| `minSpacing` | 160 | Minimum distance between two spots. |
| `minVillageDistance` | 150 | No spot this close to a village. |
| `minWalkableCells` | 40 | Room needed for a group. |
| `minScore` | 40 | How pretty a place must be to be kept. |
| `weightWater`, `weightHeight`, `weightOpenSky`, `weightFlowers` | 30, 30, 20, 20 | What counts as pretty. |
| `biomeScores` | list | Biome bonus/penalty. |
| `scoringsPerTick` | 2 | Server load of the scoring. |
| `stopChance` (step 3) | 0.25 | Chance a trip includes a nearby spot. |
| `maxDetour` (step 3) | 96 | How far off the route a unit goes for a break. |
| `pauseMinMinutes`, `pauseMaxMinutes` (step 4) | 2, 6 | Length of a break (real minutes). |

## Steps

1. Record + saved data + `ChunkReader` consumer + lazy scoring (`restspot/`). — done (build OK)
2. Population map markers + tooltip; debug command `/travellersdebug restspot here|stats|rescan`. Tune the score with the user on real maps. — done (spots look sensible in game)
3a. Clean up `TravelCommon`; one trip rule (`TripPlanner`) for loaded and virtual units. — done
3b. Rename visit → stay (`stay/`), `StayPlace` with the village branch only, behaviour unchanged; user checks village visits in game. — done
3c. Rest spot stop: insertion in `TripPlanner` (within `maxDetour`, `stopChance`), stop recognised by position (`RestSpotManager`), audit every "stop = village" assumption (dialogue and "where are you going" name the next settlement, no gift at a rest spot, worker stuck-watchdog waits for the stay). — built
4. Rest spot branch of `StayPlace` (reach, anchors in the patch, stay length from config, nothing to donate), loaded and offscreen. — built
5. One side at a time (`RestSpotManager.heldByOtherSide`, checked at planning and on arrival); groups disperse and trace events come from the stay itself. — built
