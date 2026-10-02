# Travel AI

`EntityAITravel` is the orchestrator (waypoints, pathing, mounts, stuck ladder). Helpers: `TravelTerrain` (terrain questions, loaded chunks only), `TravelRescue` (short rescue hops), `TravelBoatMode` (afloat / water edge), `TravelStuckLadder` + `StuckRecord` (stuck rescue), `TravelPathScheduler` (A* budget). Routes come from the itinerary (`pathing/CLAUDE.md`). Active plan: `docs/travel-ai-unified-itinerary-plan.md`.

## Waypoints
- A travel waypoint is an XZ column: arrival is XZ-only; its Y comes from `TravelTerrain.findGroundNear`. Every pick goes through `picked(source, …)` (logged to the trace).
- Precedence: catchup height-aware → boat mode → itinerary carrot → naive straight tiers (fallback).
- Every blind terrain-probed candidate (detours, local escape) is validated by a real A* (`TravellerNavigateGround.isReachable`) before use — a standable column proves nothing. Detour candidates may point sideways or backwards.
- Vanilla `PathFinder` never fails; it returns a best-effort partial path. Treat a path that doesn't reach its goal as a failure (`isPathStillborn`, partial-path checks).
- `EntityAITravel` refuses to run while `groupLeaderUUID != null`, unless `isCatchupTravel()`, and while `isVisitingVillage()`.

## Stuck ladder
- **One ladder, one progress measure, one record** owned by the entity (`getStuckRecord()`), never by a task. Progress = entering a cell not among the last visited ones, so pacing inside a pocket never counts.
- Rungs fire once each, in order: REPATH → DETOUR → REPLAN (or abandon an unreachable gate) → HOP (repeats, reaching further each time; candidates along the intended route, each proved by one budgeted A*) → EDGE_VIRTUALIZE (only at an unloaded edge).
- Only real progress, a successful hop/landing/gate abandon, or a new episode resets it. **Never add a reset path for convenience**, never add a parallel stuck counter — a new rescue is a new rung.
- Reachability probes cap their A* range to the probed distance (a full-range probe loads chunks).
- No hop on the tick the mount state flipped.

## Boats
- `TravellerBoats` is the one set of boat gestures (board, steer, leave), shared with `FollowBoatController`. Boarding puts a bonded horse in LED; leaving, or removing an `EntityTravellerBoat`, asks MOUNTED back.
- An itinerary-driven unit boards only from a `BOAT` segment of its itinerary (feet in water, segment under or just ahead of the cursor). The "mostly water ahead" heuristic only serves catchup and `enablePlannedRoutes=false`.
- `TravelBoatMode.tickAfloat` returns before the stuck ladder, so it carries its own backstop (`tickBoatStallBackstop`).

## Doors and fence gates
- `EntityAIPassDoor` (`setMutexBits(0)`, mirrored in the raider) + `TravellerDoorways` (type checks, open/close, ref-counted registry of doorways held open, per world).
- Opens only a closed doorway on the next few path nodes. Closes behind on a plane-crossing test; only the last holder closes, never on a living entity. A doorway a player propped open is left alone. Offscreen: inert.

## Destination choice (`travelcommon/TravelDestinationChooser`)
- The single destination authority, loaded and virtual alike (travellers, raiders inverted, non-recruited workers). Distance is the dominant term — a soft regional bias, never a hard partition (except open conflict). `TravelDestinationChooserTest` enforces the distance balance.
- Raider inversion is exactly three sign flips (conflict filter skipped, foreign culture, low relation).
- Unknown relation (`null`) = neutral, never excluded. Caches are bounded by settlement pairs, never by traveller count.
- The recent-destination penalty (anti ping-pong) stays active at any regionalization strength; home pull is stateless and never for raiders.
- Validation: `/travellersdebug destination report`.
