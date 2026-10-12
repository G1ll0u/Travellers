# Follow AI — worker escorts and traveller/raider groups

**One task, `EntityAIFollowLeader`, drives every follower** (registered by `EntityTraveller`, mirrored in `EntityTravellerRaider`, and by `EntityTravellerWorker`). Follow-context hooks (`getEffectiveFollowTarget`, `isHiredFollower`, `isJobActive`, …) have neutral defaults on `EntityTraveller` and worker overrides. A plain group follower's only "leader" is `groupLeaderUUID`.

`EntityAIFollowLeader` orchestrates; it owns `FollowGoalResolver` (where to stand), `FollowSteering` (direct steering, hazards), `FollowPathRequester` (budgeted A*), `FollowStuckSignals` (stuck evidence, cave rescue), `FollowSpeedPolicy`. Resets are built from named groups that each clear a deliberate subset — never merge them into one.

## Formations
- `FormationGeometry` is the pure slot math shared by every consumer: `FollowFormationCoordinator` (loaded workers; registration, settings, cave mode — worker-only), `GroupFormationTracker` (loaded groups; dense rank, smoothed heading), `VirtualFormationSimulator` (offscreen workers).
- `COLUMN` is internal (cave single-file); `RING` is groups-only. A group's style is rolled once at creation, group-atomic, persisted. Fully offscreen groups always use the ring spread.
- Worker-only coordinator methods are reached through `requireWorker()` inside an `instanceof EntityTravellerWorker` branch.

## Attitude (workers)
- ATTACK / DEFEND / HOLD per group. DEFEND/HOLD use a leash (`EntityAIAttackMeleeCustom.withinTether`) gated only at task start/continue; inside the leash they fight like ATTACK. Virtual battles mirror it exactly (`VirtualFormationSimulator`).

## Catch-up and the movement hand-off
- `FollowModeSelector` (always constructed; CAVE transition worker-only) enters CATCHUP with a debounce. Catchup runs `EntityAITravel`; the two tasks hand the movement mutex over purely on `isCatchupTravel()`.
- Catchup must not end on distance while the stuck ladder escalates (`StuckRecord.isEscalating`).
- A loaded follower whose leader is offscreen is driven by catchup travel toward a slot beside the leader's virtual position — never a private navigator. **The group version must clear `groupLeaderUUID` once the leader is confirmed gone**, or the follower idles forever.
- An escort's virtual goal is re-derived from the leader on every refresh; `refreshGoal` precedence: catchup → mission → go-order → escort slot → follow-owner.

## Stuck followers
- They use the travel AI's rescue (`StuckRecord`), not their own: past the stall threshold, with evidence of no working route, a surface follower is handed to catchup with the evidence preloaded (`handOverToCatchup`). Only workers' CAVE mode keeps its own rescue.
- A PathScheduler denial also counts as failure evidence once direct steering has failed too. Priority escalates when steering is blocked or paths keep failing (`getPathPriority`).
- `steeringBlockedTicks` is reset only by real movement success, arrival or a task/target reset — never by a "the way ahead looks clear" probe (that pinned it and escalation never fired).
- **Never teleport a follower to catch up** with a leader that walked away; teleport is a stuck-rescue last resort. Every follower eventually reaches its slot.

## Mounted followers
- `MountedFollowPacer` steers while the leader moves (lead point from the **smoothed** leader velocity, gated by the moving threshold — never the raw per-tick vector); the settle servo handles the stopped case.
- Every mounted follow steer goes through `TravellerNavigateGround.steerDirectLegCapped`; when it refuses, retrace the leader's trail (`leadTrail`, or `PlayerLeaderTrail` for a player leader) before counting a block.
- Distances (catch-up, arrival, settle) are mount-scaled. `isMountWedged` runs before hazard heuristics. The `followerMode` fast path excludes mounted bodies.
- `FollowRouteCache` is world-wide (spatial buckets + bearing filter); `MIN_SNAP_DISTANCE` keeps a cached waypoint from landing under the follower's feet.

## Other
- `FollowBoatController` / `FollowFlightController` are the shared boat/elytra halves.
- A transport worker returning to its owner takes the pickup-escort slot until loot is collected.
- Group operations for hired workers: `follow/group/WorkerGroupService` only (create, assign, merge, make leader, go-to, convoy follow…), each followed by its mandatory `epilogue`. "Is a leader" = at least one worker, loaded or virtual, escorts it; use `countFollowers()` for player-facing counts, never the loaded-only `getLeaderFollowerCount()`. Busy workers are skipped via `WorkerOrderGuard`.

## Formation Hub map
- Units are drawn at glided positions (`WorkerMarkerSmoother`, keyed by unit UUID); hit-tests use the drawn position and `hitRadiusPx`, never the raw `pos`, and markers are rebuilt every frame.
- A grouped follower stands for its group (`followLeaderUuid`) on click; single followers only by shift/rectangle. Every order (map, leader list, action bar) goes through `FormationOrdersMenu`.
