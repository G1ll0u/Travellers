# Pathing: navigator, itinerary, terrain grid, coarse routes

- `TravellerNavigateGround` / `TravellerNodeProcessor` / `TravellerMoveHelper`: loaded-side A* and movement. Mounted searches read a per-search cached block access (planning only); `MixinPathFinder` raises the node cap only for `TravellerNodeProcessor` searches.
- **Our path searches never load a chunk**: `TravellerNavigateGround.search` reads through `LoadedOnlyChunkCache` (an unloaded chunk reads as blank); never call vanilla's `getPathToPos`, never gate a search on a loaded area.
- `PathBody` + `MountTerrainProfile`: the one clearance model (see `mount/CLAUDE.md`).

## Itinerary (`itinerary/`)
- A unit's current leg is an `Itinerary` (immutable XZ polyline, per-segment kind `WALK`/`ROAD`/`BOAT`/`GATE`) + an `ItineraryCursor` (never moves backwards), held in an `ItineraryState` owned by `EntityTraveller` **and** `VirtualTravellerRecord`.
- **Trip ≠ itinerary**: the trip is the ordered village stops (`TravelCommon.route` ↔ `tripStops`); the itinerary is the current leg only. Both are handed over at virtualize/materialize.
- **`ItineraryPlanner` is the single place a route is decided**, both sides: straight bridges laid at once, upgraded on the planner thread by one A* over the terrain lattice plus the road network (`RoadLayer`, from Ultreïa) plus boats (water edges). Walled villages: departure/arrival gates are fixed `GATE` vertices; other walled villages are obstacles for walked edges.
- Who follows an itinerary: `EntityTraveller.isItineraryDriven()` loaded ↔ `VirtualTravellerManager.routeDrivenMovement(r)` virtual — keep them mirrors. Never a group/escort follower, catchup or return-to-owner.
- Loaded: `EntityAITravel.ensureItinerary` keeps a handed-over itinerary or builds one; the carrot is grounded at the road's own height on `ROAD` segments. Too far off the route → `replanItineraryFromHere`. Stuck at a gate → excluded gate + rebuild.
- Budget: `ItineraryPlanner.tryAcquire` for builds, bridges and virtual destination picks. Bump `ItineraryState.FORMAT_VERSION` when build rules change.
- Kill switch: `ROUTING.enablePlannedRoutes=false`.

## Terrain grid (`terrain/`)
- Coarse per-dimension disk-backed grid, one cell per chunk, measured from chunks handed over by `ChunkReader`; `BiomeTerrainEstimator` fills the rest. **The planner never sees an unknown cell** (`ensureBoxResident` before `ensureEstimated`) and only reads an immutable `TerrainGridView` snapshot.

## Coarse routes (`route/`)
- `CoarseRouteSearch`: 8-connected A* on a lattice matching the grid spacing (16), node-budgeted, always returns a best-effort polyline. Each edge is sampled along its whole length; every cost term stays spacing-invariant.
- `RouteCache` (per 128-block bucket) must keep per-segment kinds.
- `VirtualTravellerManager.drainRoutePlans` is the one caller of `TravelRoutePlanner.drainResults`.

Debug: population map "Route" button, `/travellerroutedump`, trace events `itinerary …`, `ROUTING.debug`.
