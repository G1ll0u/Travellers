Status: steps 1-6 written and built, awaiting in-game scenario runs

# Group movement: the group walks the leader's route

## Goal
Groups (traveller/raider groups and hired-worker groups) move as one body along the leader's itinerary instead of copying the leader's feet: they don't follow the leader's mistakes (falls, detours), they file along Ultreïa roads and bridges, and a group survives its leader's death. Minimal performance cost at any group size.

## Decisions taken with the user
- Applies to both kinds of groups.
- Succession: when the leader dies, the member with the best armour + weapon takes over the leader role and its destination. The group is never dissolved by a leader death.
- Followers aim at slots around a **route anchor** (the leader's progress along its itinerary), not around the leader's position.
- Leader leaves the route (fall, detour): the anchor stops at that point and the group waits until the leader is back on the route.
- Off road: the current formation shape, oriented by the route direction.
- On a `ROAD` segment: the group narrows into a file along the road polyline itself (each follower N steps behind on the route). Width is **measured on the spot** (standable blocks across the road, loaded chunks only): two abreast on a wide road, single file on a bridge or narrow passage. Narrowing starts a little before the road/bridge.
- Hired workers: on a road they switch to the file and return to their Formation Hub formation on their own after it.
- Offscreen: a group that materializes on a road reappears filed along it.
- The leader waits for a lagging group by slowing down, never by stopping: no stuck-rescue false alarm, no deadlock. Only a real gap counts (over 8 blocks beyond a follower's place, x1.75 mounted; slowest 60 % pace at 20 blocks), and while it slows its followers walk their own travel pace instead of matching it (in-game test: matching it locked the group at ~30 % pace). Followers fighting, visiting, catching up from far away or making no headway for 10 s are not waited for.
- The road file uses the formation's own distances: escorts first, just behind the leader, then the rest from the follow distance at the formation's row spacing (user, after the in-game test: escorts almost in contact, followers at the follow distance).
- Performance: one computation per group per second (pure polyline math, no A*, no chunk load); followers on a road steer directly along it.

## Steps
1. Succession — traveller/raider groups (loaded, virtual, after a virtual battle) — built (`follow/GroupSuccession`, autotest `group-succession[-virtual|-vanished]`), not yet run in game
2. Succession — hired-worker groups, with an owner notification — built (`WorkerGroupService.onLeaderDied`; successor inherits the Go/hold/follow/convoy order and the formation settings; autotest `worker-succession[-virtual]`), not yet run in game
3. Route anchor + route-oriented formation for groups (loaded) — built (`follow/GroupRouteAnchor`, used by `GroupFormationTracker`; trace events `group anchor frozen/released`; autotest `group-anchor`), not yet run in game
4. Road file with measured width + look-ahead narrowing (loaded), leader slows for a lagging group — built (`follow/GroupRoadFile`, `GroupFormationTracker.leaderPace` applied in `EntityAITravel.getActiveSpeed`; trace events `slowing for the group` / `group caught up`; autotest `group-road-file`), not yet run in game
5. Same for hired-worker escorts on a go order, with automatic return to their formation — built (`GroupFormationTracker.routeSlotForEscort` from `EntityAIFollowLeader.resolveWorkerSlot`, route heading fed to `HeadingTracker.steerToward`; autotest `worker-road-file`), not yet run in game
6. Virtual side: materialize positions from the road file — built (`GroupRoadFile.materializePlace`, used by `MaterializePositionResolver` for group followers and Go-order escorts, single file; autotest `group-road-file-reappear`). Offscreen goals stay the ring: nothing is seen there, an offscreen leader never falls, and the group moves as one, so anchor and pace have nothing to simulate. Not yet run in game
7. Trace events + profiler check on a large group — pending
