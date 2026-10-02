# Travel AI redesign — one itinerary, shared by loaded and virtual units

**Status (2026-10-02)**: phases 1–3 built, playtest ongoing. Phase 4 partly done (boat mode, stuck ladder and
rescue already split out of `EntityAITravel`; fixed-goal missions follow itineraries); remaining: shrink
`EntityAITravel` to an orchestrator, delete dead helpers. One phase at a time, each confirmed in game first.

## 1. Goal and non-negotiables

Travellers, raiders and workers must take **the most sensible, coherent route for their environment**
— roads when roads make sense, around a mountain or a bay when that is shorter in effort, a boat when
walking around is clearly worse — and move through the world so naturally that players think "they
really travel well".

Hard constraints from the user:
1. **Performance**: there can be **thousands** of units (solo, groups, virtual or loaded). Nothing
   per-unit-per-tick may read blocks or allocate on the virtual side; planning is async and shared.
2. **Loaded and virtual must behave the same.** Virtualization exists so units keep living on
   unloaded chunks: a loaded unit reaching the edge of loaded terrain gets frozen by vanilla, so it
   virtualizes, and rematerializes when it re-enters loaded chunks. The player must never notice the
   hand-off. The smaller the gap between "virtual" and "real" behaviour, the better.
3. **Surface travel.** A waypoint is an XZ column; its height is incidental. A traveller goes where the
   waypoint says, *the way its surroundings allow* — a waypoint under a tree never means "climb the tree".
4. **Never force-load chunks** (existing invariant — see CLAUDE.md "Universal virtualization").

## 2. Decisions already taken with the user (do not re-litigate)

| Topic | Decision |
|---|---|
| Model | 3 levels: **itinerary** (hundreds of blocks, chosen once per leg) → **carrot** (point 20–30 blocks ahead *along* the itinerary, forward-only cursor) → **step-by-step** (vanilla A*, mounted direct ride, doors, gap jumps — unchanged) |
| Shared itinerary | **Foundation, phase 1**: one itinerary object owned by the unit, handed over with its cursor at virtualize/materialize. Virtual units glide along it, loaded units walk carrots along the same line |
| Route choice | **One cost currency** inside one search: land = 1/block + terrain terms; Ultreïa (MPB) road = 1/1.5 per block (a road up to ~1.5× longer than direct is accepted); water = crossed by **boat** at 2× walking cost per block + a fixed embark cost (walk around unless the detour is > ~2× the crossing; no boat for every stream). All multipliers are config fields |
| Sea behaviour | Follow the coast, but take the boat **without hesitating** when the itinerary says so — boat segments are decided in advance, **no oscillation at the shoreline** |
| Village gates | Become ordinary itinerary vertices (not a parallel override system) |
| Stuck handling | One measure ("is the cursor advancing along the itinerary?"), one ladder: repath → reachability-validated local detour → **replan itinerary from here** (new) → short TP hop → edge virtualize (existing rule) |
| Scope | Travellers; raiders (roads only if `SPAWN_RAIDERS.raidersUseUltreiaPaths`); non-recruited workers; **recruited workers on fixed-goal missions** (go-to, transport, go home, duty) — *with* roads under the 1.5× rule |
| Exceptions | **Catchup toward the player** (moving goal): no itinerary, aims at the player directly, but shares carrot/ground resolution/stuck ladder. **Group followers**: unchanged, they follow their leader (only the leader owns an itinerary) |
| Saves | No migration: an in-flight old-format route is dropped on load, the unit keeps its destination and rebuilds its itinerary |

## 3. Current state (verified 2026-09-28)

### 3.1 There are really two travel AIs
- **Loaded**: `entity/entityai/travel/EntityAITravel.java` (~4200 lines). `computeNextWaypointToward`
  picks the next waypoint from 5 competing sources, in precedence: debug forced waypoints →
  village gate (`maybeGateWaypoint`, `world/mill/VillageGateIndex`) → height-aware catchup → live MPB
  road (`world/pathing/MPBRouteTracker`) → loaded coarse plan (`plannedLegWaypoints`, only when MPB
  has no road, `ROUTING.usePlannedRoutesLoaded`) → naive straight line → small detours → random reseed.
  Each has its own arrival/advance rules.
- **Virtual**: `world/virtualization/VirtualTravellerManager.java` (`buildRouteTo`,
  `tickRoamRoute`, `advanceRouteCursor`, `advanceVirtual`, `refreshGoal`, `routeDrivenMovement`) +
  `VirtualRouteAssembly` (MPB legs **verbatim**, coarse terrain A* only for the bridges between them).
  `r.travelRoute` holds that polyline — but at virtualize a roaming worker's *village stop list*
  (`TravelCommon.route`, a different granularity) is copied into the same field. Keep those two
  concepts separate in the new design: **trip** = ordered villages (TravelCommon), **itinerary** =
  the path polyline for the current leg.
- They do **not** pick the same path, and at materialize the virtual polyline is discarded and
  `EntityAITravel` replans from scratch — that is the "frontier" the user wants gone.

### 3.2 Pieces worth keeping
- `world/pathing/route/`: `CoarseRouteSearch` (8-connected A* on a lattice, `coarseNodeSpacing`,
  node budget, edge sampled along its length), `TravelCostModel`, `RouteCostParams`,
  `TravelRoutePlanner` (thread pool + result queue + `RouteCache`), `RoutePlanScheduler`,
  `RouteCacheKeys` (bucket = coords >> 7), `LoadedRouteRequests`.
- `world/pathing/terrain/`: disk-backed coarse height/water/roughness grid, immutable
  `TerrainGridView` snapshots for worker threads.
- `MPBIntegration` → Ultreïa's `MPBRoadAPI.findRoadRouteLegs` (source in
  `/home/user/IdeaProjects/MillPathBuilder`, `MPBRoadNavigator.findRouteLegs`). **It snaps to the
  nearest road waypoint to the origin and to the destination — even if behind — with no detour
  check.** Road polylines have a waypoint every ~4 blocks.
- `EntityAITravel` helpers added 2026-09-28: `findGroundNear`/`groundInColumn` (waypoint Y = the
  standable cell or water surface closest to the walker's height, scanning through trees/man-made
  blocks, never natural terrain, shifting off trunks), XZ-only `isRoadWaypointReached`,
  projection-based `nextPlannedLegWaypoint`, `picked(src)` trace events (`waypoint x,y,z src=.. fwd=..`).
- Mounted travel (`tryMountedDirectRide`, LED triggers, `TravellerMountSupport`) — keep as the
  step-by-step layer; read the horse-mounts section of CLAUDE.md before touching anything near it.

### 3.3 Known problems this redesign removes
- Waypoint sources fight each other; only MPB has a monotonic cursor → U-turns (the 2026-09-28 bug:
  cached coarse plan vertex 0 = someone else's origin, 55 blocks behind).
- The naive straight tier accepts sea surface as a waypoint; boat use is decided only once feet are
  wet (`shouldUseBoat`: "mostly water ahead") → shoreline oscillation.
- ~10 overlapping stuck counters (`noMoveTicks`, `noProgressTicks`, `pathNoAdvanceTicks`,
  `stuckTicks/stuckStage`, pocket window, underground, `detourGraceTicks`, stillborn, 
  `consecutiveNoWaypoint`, road-suppression ticks), some of which have starved each other before.
- Boat code duplicated with `entity/entityai/follow/FollowBoatController`.
- Dead helpers in `EntityAITravel` (0 callers, verified): `isFarEnoughFromAnyPlayer`,
  `isLikelyInCave`, `isCaveLike`, `effectiveTopY`, `surfaceAtOffsetDryFirst`,
  `lateralDistanceToLine`, `canRepathNow`, `findBoatWaterSurface`, `RETURN_SPEED_MULT`, no-op `logState`.
- `ROUTING.preferMpbRoads` exists in config but is read nowhere.

## 4. Target architecture

### 4.1 `Itinerary` (pure data, no world references)
- Polyline of XZ vertices (Y = coarse hint only), each **segment** tagged with a kind:
  `WALK`, `ROAD`, `BOAT`, `GATE` (a gate vertex), plus the destination and a `source/quality` for traces.
- Compact: road runs are simplified (Douglas–Peucker, few-block epsilon) before storing — a raw MPB
  leg is hundreds of points, and thousands of records persist this.
- Serializable to NBT (compact int arrays). Lives on `EntityTraveller` **and** `VirtualTravellerRecord`,
  handed over as-is (with the cursor) at virtualize/materialize.
- Immutable once built, so one instance can be **shared by every unit on the same leg** (cache).

### 4.2 `ItineraryCursor` (per unit, tiny)
- Segment index + projected position. `advance(posX, posZ)`: project onto the current and next few
  segments, take the nearest, **never move backwards**. O(1) amortized, allocation-free — this is the
  virtual per-tick hot path.
- `carrot(distance)`: the point `distance` blocks ahead along the polyline from the projection
  (the loaded side resolves it to ground with `findGroundNear`).
- `offRouteDistance()`: distance to the polyline, for "pushed far off → replan" (currently
  `TRAVEL.plannedRouteMaxOffRouteBlocks`).
- The virtual side moves the unit **along** the polyline by `simStep` per sim tick (replaces
  `advanceRouteCursor` + straight goal-chasing for route-driven records).

### 4.3 `ItineraryBuilder` (the only place that decides the route)
- Input: origin, destination, body (on foot / mounted), may-use-roads flag, culture (for Plan B terms).
- Server thread: fetch MPB legs (in-memory network; check whether Ultreïa's API is thread-safe
  before moving it off-thread — ask/verify, don't assume). Capture a `TerrainGridView`.
- Worker thread: **one A*** in the single cost currency (§2): lattice edges cost terrain as today; road
  edges from the MPB legs at `1/roadPreference`; water edges allowed as boat at
  `boatCostFactor` + a fixed embark cost (only for water deep/wide enough per the terrain grid).
  Gate vertices inserted where the leg crosses a walled village ring (`VillageGateIndex` data is
  Millénaire resident data — no block reads — so the builder can do it for both sides).
- Output an `Itinerary`; cache it keyed per (origin bucket, destination settlement, body, roads flag).
  Projection-based cursors make sharing safe — this is what makes thousands of units affordable.
- Until the plan arrives, the unit uses a 2-point straight itinerary (today's behaviour), then swaps
  in the real one and re-projects its cursor (never backwards).

### 4.4 Loaded follower (`EntityAITravel`, slimmed)
- Each waypoint change: `cursor.advance(pos)` → `carrot(step)` → `findGroundNear` → vanilla A* /
  mounted direct ride. Arrival XZ-only.
- On a `BOAT` segment start: embark (reuse/merge with `FollowBoatController`), steer along the
  segment, disembark at its end. No "is there a lot of water ahead?" heuristic anymore.
- `GATE` vertex: just a vertex, carrot tightened near it so the unit actually threads the gate.
- Stuck ladder (§2) driven by one progress signal: cursor projection distance advancing over time
  (plus a raw no-movement guard for a hard pin).

### 4.5 Virtual follower (`VirtualTravellerManager`)
- Route-driven records: move along `itinerary` via the cursor at `simStep` (mounted/boat speed
  factors applied per segment kind — a `BOAT` segment moves at boat speed offscreen too).
- `refreshGoal`/`tickRoamRoute` keep ownership of trip-level decisions (next village), but call the
  same `ItineraryBuilder`. Delete `VirtualRouteAssembly`'s own assembly once the builder covers it.
- Materialize: spawn at the record position (already on the itinerary), **keep itinerary + cursor**.
  Virtualize: capture them. Define what happens when materializing mid-`BOAT` segment (ask the user:
  spawn already in a boat vs. wait until ashore).

### 4.6 Performance budget (check with `/travellersdebug profile` and a Spark profile)
- Virtual per tick per unit: cursor advance + one step — no block reads, no allocation.
- Itinerary builds: async, cached per leg; groups share the leader's; budgeted by the existing
  `RoutePlanScheduler`. Measure builds/minute and cache hit rate; expose them in a debug command.
- Loaded: `findGroundNear` only at waypoint changes (every few seconds), never per tick.

## 5. Phases

Each phase: explain to the user → ask what's open → implement → build → user playtests with a trace
(and a profile for perf phases) → fix → update CLAUDE.md/memory → only then next phase.

### Phase 1 — Shared itinerary foundation
- `Itinerary`, `ItineraryCursor` (+ JUnit tests: projection, never-backwards, carrot across vertices,
  off-route distance, NBT round-trip).
- Build itineraries with **today's sources only** (MPB legs + coarse bridges, i.e. what
  `VirtualRouteAssembly` produces, plus straight fallback) through one entry point used by both sides.
- Loaded `EntityAITravel` consumes the itinerary via cursor+carrot instead of its 5-way precedence
  (gates stay as they are this phase; debug forced waypoints become a forced itinerary).
- Virtual side moves along it with the cursor. Hand-off keeps itinerary + cursor both ways.
- Trace: log itinerary (source, vertex count, segment kinds) on build/swap; `waypoint src=itinerary`.
- Acceptance: no U-turns; a unit crossing the loaded/virtual border keeps the same line
  (trace position vs. itinerary before/after hand-off); virtual pace ≈ loaded pace.

### Phase 2 — Sensible route choice (single cost currency) + boat segments
- Road edges and boat edges inside the coarse A*, config fields (`roadPreference` 1.5,
  `boatCostFactor` 2.0, embark cost, minimum water width for a boat).
- `BOAT` segments consumed on both sides; remove the shore-triggered boat heuristic.
- Gates move into the builder as `GATE` vertices; remove `maybeGateWaypoint`'s override machinery.
- Remove/replace the unused `ROUTING.preferMpbRoads`.
- Acceptance (with the user, in game): a coastal trip follows the coast and embarks without dithering;
  a road detour > 1.5× is not taken; a walled village is exited through a gate.

### Phase 3 — Unified stuck ladder
- Replace the ~10 counters with the single ladder of §2, including "replan itinerary from here".
- Preserve the invariants CLAUDE.md lists under "Travel stuck escalation" in spirit (no starvation of
  the rescue by the detour, reachability-validated detours, never virtualize a unit stuck on loaded
  terrain, `tryVirtualizeStuckAtEdge` only toward unloaded ground).
- Acceptance: movement profiler stall/rescue counts equal or better than before over a long session.

### Phase 4 — Cleanup and split
- Boat logic shared with `FollowBoatController`; delete dead helpers (§3.3); split `EntityAITravel`
  into orchestrator (~600–800 lines) + itinerary follower + stuck ladder + boat.
- Extend scope to recruited workers on fixed-goal missions (go/transport/home/duty) with roads;
  catchup keeps the direct-to-player path but on the shared cursor/ground/stuck code.
- Rewrite the travel sections of CLAUDE.md (they currently describe the old tiers).

## 6. Things to ask the user when you reach them
- Materializing mid-boat-segment: already in a boat, or hold virtual until ashore?
- Minimum water body worth a boat (a river 8 blocks wide: swim, ford, or boat?).
- Whether mounted units should weigh roads differently (horses on roads feel natural).
- Any tunable default you are unsure about — propose a value and ask.
- Anything where virtual and loaded behaviour would visibly differ — the user cares about this most.

## 7. Invariants to preserve (from CLAUDE.md, verify in code)
- `EntityTravellerRaider.initEntityAI()` replaces tasks without `super` — mirror any new AI task.
- `EntityAITravel` refuses to run while `groupLeaderUUID != null` unless `isCatchupTravel()`.
- Loaded XOR virtual record — enforced at materialize / virtualize / `TravellerLoadDedupHandler`.
- AI state flags reset unconditionally in `materializeEntity()`; nullable fields too.
- Mounted: steering stays vanilla, `PathBody`/`MountTerrainProfile` single clearance model, LED
  triggers, mounted speed mirroring — the itinerary work must not add a second clearance model.
- Elytra flight lives inside `EntityAITravel`; a flight must still be able to target the carrot.
- Never force-load chunks; `findGroundNear`/waypoint probes must stay loaded-only.
