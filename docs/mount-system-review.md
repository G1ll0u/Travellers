# Mount system review: why riders get stuck in forests, and a simpler design

**Decision (user, 2026-10-06): keep the realistic model — the real 1.4-wide horse plus its rider — and fix
its defects (alternative A, section 6), measuring first (section 7 step 1).** The narrow-horse redesign in
section 5 is rejected: the user prefers realism to a pair that clips through trunks. Sections 5 and 7 steps
3-7 are kept only as background; section 8's before-numbers still apply.

Status: review done; fixes not started. Nothing under `src/` is changed. Everything below was checked against the code,
vanilla 1.12.2 sources, the test server (10-min soak on 2026-10-06, jar c850cd73 + local changes), trace
files and an offline read of the test world's region files.

## 1. Short answer

- **Why riders get stuck.** The mod plans and steers for a 1.4-wide, 1.6-tall horse. But it only checks
  where the horse ends up, never the space the horse moves through to get there. Vanilla physics checks
  that space. In forests the two disagree in three ways (section 3). On top of that, the recovery layers
  sometimes cancel each other out, so a stuck rider never gets unstuck (30 s or more in the traces).
- **What I recommend.** Make a bonded horse's collision box no wider than its rider (about 0.6 instead of
  1.4; height stays 1.6). A traveller is already 0.7 × 2.05 and its planner already demands 3 free blocks.
  So the mounted pair fits wherever its rider can walk, by construction. Riding then reuses the on-foot
  planner and movement as they are, and a led horse can always follow its rider's footsteps.
- **What it removes.** About 1,500 lines and about 25 config keys: the second clearance model
  (`PathBody`, `MountTerrainProfile`), the 2×2 mounted node generator, corridor checks, direct ride,
  planned and reactive jumps, sidesteps, wedge detection, dismounting because of terrain (plus its
  on-foot reachability probe) and the larger node budget.
- **The cost.** The user has to accept these, because some reverse earlier decisions:
  - In 1-block gaps the horse model visibly overlaps trunks.
  - Horses stop jumping 2-block ledges.
  - Riders follow paths instead of riding straight lines.

## 2. Map of today's system

Sizes are line counts. "Mount share" means how much of a mixed file is mount code.

| Concern | Where | Size |
|---|---|---|
| Bond identity, modes (MOUNTED/LED/HITCHED), mount/dismount/leash, LED driving, recalls, jumps, sidesteps, speed mirroring, capture/restore | `mount/TravellerMountSupport.java` | 1,960 |
| Per-rider transient mount state (wedge counters, jump yaw, LED pacer, remount timers, lead trail) | `unit/TravellerMountState.java` | 424 |
| Which body has to fit: horse while mounted | `pathing/PathBody.java` | 94 |
| Mounted clearance model: per-column footprint fit, corridor check, climb scan | `pathing/MountTerrainProfile.java` | 308 |
| Mounted A*: 2×2 cardinal nodes, rider-band costs, hop candidates, start-node alignment | `pathing/TravellerNodeProcessor.java` (mounted share about 350 of 824) | |
| Mounted path following, corridor shortcut, turn gate, `steerDirect`, on-foot reachability probe | `pathing/TravellerNavigateGround.java` (mounted share about 300 of 1,251) | |
| Turn cap, pivot in place, slow for turn, mid-jump yaw freeze | `pathing/TravellerMoveHelper.java` | 129 |
| Direct ride first, terrain dismount, water LED, mounted reach radius | `travel/EntityAITravel.java` (about 200 of 2,053) | |
| Mounted follow: pace follower, corridor-proven steering, trail fallback | `follow/MountedFollowPacer.java` (318) + `EntityAIFollowLeader.java` (about 150) | |
| Water and melee LED entry, led pace | `mount/MountedLeadController.java` (145), `combat/ai/EntityAIAttackMeleeCustom.java:246` | |
| Load, unload, sync, death and theft guards | `mount/MountBondLoadHandler`, `MountSyncReconciler`, `OrphanMountCleanup`, `MountReleaseLedger`, `MountHazardGuard`, `MountFriendlyFireGuard`, `MountDefenseHandler`, `horse/*` | about 1,000 |
| Virtualization / materialization of mounts | `virtual/VirtualTravellerManager.java` (`captureMountIfBonded` :2144, `restoreOrBondMount` :2248), `MaterializePositionResolver.findClear{Mount,Horse}Footprint` :645-737 | |
| Mixins | `MixinEntityLiving` (keeps the leash, skips vanilla's rider-to-vehicle path mirror), `MixinEntityCreature` (cancels leash steering), `MixinEntityHorse` (lock sync), `MixinEntityTracker` (tracking range), `MixinPathFinder` (node budget), `MixinChunk` (rider saved outside its chunk) | about 380 |
| Config | `TravellersConfig.Mounts` (66 fields, :1604-1846) plus about 8 mounted follow/melee/scheduler fields elsewhere | |

How a ridden tick works today:

1. The rider's AI sets a move target.
2. `TravellerMoveHelper` caps the turn at 15°/tick and pivots in place above 55°.
3. `AbstractHorse.travel()` copies the rider's yaw and `moveForward` (vanilla `AbstractHorse.java:879-934`).
4. `tickMount` (`TravellerMountSupport.java:302-436`) mirrors the speed, then runs the reactive jump
   (:547), the sidestep (:438) and the diagnostics.

Planning sizes everything from the horse (`TravellerNodeProcessor.java:80-97`): a 2×2-cell footprint,
2 cells of height, and the rider's head band only as a cost (:299-326).

Travel tries a straight "direct ride" first (`EntityAITravel.java:1399`), proven by
`MountTerrainProfile.corridorClear` (:198). A* is the fallback. Failure leads to sidestep, then a
sidestep give-up LED (:438-519), then a terrain LED with an on-foot reachability probe
(`EntityAITravel.java:1304`), then a timed remount (:1337), then a teleport recall (:1215).

## 3. Root causes of "stuck on low foliage / against a trunk"

### 3.1 Clearance is checked at resting positions, never on the way up (low branches over a step)

Vanilla's step-up (`Entity.java:878-938`) first raises the **current** box in place, by up to
`stepHeight` (1.0 for a horse, `AbstractHorse.java:97`), and only then moves sideways. So a horse taking
a 1-block step needs free space up to floor + 1.6 + 1.0 = **2.6 above the takeoff spot**.

Nothing in the mounted model checks that:
- `findMountedWalkOptions` checks 2 free cells and the body box at the **destination** only
  (`TravellerNodeProcessor.java:469-489`).
- `corridorClear` sweeps the body at each sample's own resting height (`MountTerrainProfile.java:226-234`).
- The rider band above 1.6 is only a cost for leaves and branches (:307-326), because they do not suffocate.

So the planner happily routes under a canopy 2 blocks above the floor. On flat ground that is fine. At
the first 1-block rise, physics refuses the move.

Evidence:
- **Trace** `XipilliIztahuatzin_3bf3a1d6_031300/031403/031502_auto.log`: 3 captures, 300 samples, 30 s
  each, the same spot (-1574.3, 63, -356.3) in swamp woodland, `hcoll=1 hdxz=0`. Read from the region
  file, the grass ahead is 1 block higher, and a Dynamic Trees oak branch sits at floor+2 (-1574, 65, -356).
  It overlaps the horse box by 0.02 block, which caps the rise at 0.78 < 1.0. The corridor the follower
  was steering on approves this exact move (destination box clear).
- **Terrain statistics.** I replayed the mounted planner's rules over 64×64-block windows of the real world
  (`scratchpad/phantom.py`; vanilla collision boxes, Dynamic Trees branch radii). This counts step-ups the
  planner accepts but physics refuses:

  | Area | Step-ups refused by physics | All moves |
  |---|---|---|
  | Dense extreme_hills | 4.3 % | 0.8 % |
  | forest_hills by the user's base | 4.3 % | 0.5 % |
  | Swampland | 2.3 % | 0.2 % |
  | Open outback | 0.4 % | 0.0 % |
  | **Same terrain, 0.7-wide horse planned with the rider's own 3-cell rule** | **0** | **0** |

  A 100-block forest route crosses roughly 100 moves, so a route has a 40-55 % chance of containing
  one such trap.

### 3.2 Leaves count as ground and as something to jump onto

`MountTerrainProfile.standingSurfaceY` (:127-161) accepts any block with a collision box as a standing
surface. Leaves qualify, and with `vanillaLeavesCollision=true` in `config/dynamictrees.cfg`, Dynamic
Trees leaves have full collision. So:
- The reactive jump and the hop candidates aim at the tops of bushes.
- A horse pressed into undergrowth jumps "rise=2.00" into foliage again and again.

Trace `YunusYavuz_604a4565_123754_auto.log` (this soak, swamp at -1600, -335): 5 jumps with no progress,
then 13 consecutive "canopy or overhang directly overhead" rejections, then 3 sidesteps, then the give-up
LED. This is the "stuck against low foliage" the user describes.

### 3.3 Zero lateral margin against trunks

- The 2×2 node footprint leaves 0.3 block on each side of a 1.4 body (`PathBody.nodeCentreOffset`).
- The direct-ride corridor sweeps exactly the body's half-width (`TravellerNavigateGround.isBodyCorridorClear`
  :489-519). That is zero margin.
- The horse cannot follow that line exactly: a 15°/tick turn cap at about 0.33 block/tick gives a turning
  radius of about 1.3 blocks, plus momentum.
- The turn gate that bounds this bow exists only for the path shortcut (:544). `steerDirect` (:615), used by
  direct ride and by every mounted follower, does not have it.

Trace `YunusYavuz…` t=860-896: two wedges with the body against an oak branch above the step band.

This class is real but less frequent in my sample than 3.1 and 3.2.

### 3.4 Recovery layers cancel each other, so a wedge becomes permanent

- **Follow flicker.** When `isMountWedged` is true, `EntityAIFollowLeader` returns without steering
  (:1674-1677), so the next tick reads `moveForward=0`. That tick:
  - resets the sidestep counter (`trySidestepIfWedged` needs 10 consecutive wedged ticks), and
  - steers again; `steerTo` resets `steeringBlockedTicks` on every approved steer (:1791-1802).

  The result alternates `fwd=0.314 / 0` every tick. Sidestep, jump and path escalation never fire. Xipilli
  shows **0 sidesteps in 30 s**.
- **A give-up LED is undone after 6 ticks.** `EntityAITravel.resetTask` restores MOUNTED whenever a
  terrain-fallback LED is armed (:313-317). When the follow AI takes over, the LED that
  `trySidestepIfWedged` had just chosen is cancelled. Yunus t=864 → 870.

### 3.5 What the current instruments can and cannot see

- **Soak zones have almost no forest on traveller routes.** In two soaks, the stall biomes are swamp,
  outback and plains. I added 4 forest zones to this soak (dense areas found from the region files); no
  stall happened in a forest biome.
- **The trace terrain snapshot has no block names.** It gives heights and free space for a pedestrian, so
  "leaf vs trunk vs rock" had to be read offline from region files.
- **Short snags are not traced.** `MOUNT_SIDESTEP` (100-120 per run) counts snags but not their cause.
  Auto-traces fire only on stalls of 2 s or more.
- **The flicker deadlock (3.4) adds nothing to `MOUNT_SIDESTEP`.** It only shows up as a long stall.

## 4. Where the complexity is and whether it pays

| Piece | Lines (about) | Pays? | Evidence |
|---|---|---|---|
| 2×2 footprint + `fitFootprint` per-column tolerance + start alignment | 250 | No | It exists only because a 2-cell body has two columns that disagree. A 1-cell body has no such problem. |
| Rider-band cost (`classifyStackClearance`) + `MountHazardGuard` suffocation | 120 | No | It routes under canopies that physics then refuses (3.1). |
| Direct ride + `corridorClear` + `steerDirect` + turn gate | 450 | Weak | Mounted travel efficiency is 0.76 / 0.63 against 0.72 / 0.70 on foot (two soaks): no real gain. Zero-margin chords cause 3.3. |
| Planned hops + reactive jump + climb scan + jump physics + yaw freeze | 300 | No | Jumps into foliage and repeated canopy rejections (3.2). |
| Sidestep + streak + give-up LED + `isMountWedged` | 150 | No | 100-120 sidesteps per run; it never fires in the flicker case; its LED is undone (3.4). |
| Terrain LED + on-foot reachability probe + terrain remount timers | 200 | No | 165-230 terrain LEDs per run. Each ends in a "mounted but on foot" phase, the source of most recalls (below). |
| LED horse: trail follower + horse A* fallback + snag recall | 200 | Partly | `LED_HORSE_JAMMED` 210-252 per run. A 1.4 horse cannot always fit where a 0.7 rider walked. |
| Node budget mixin | 40 | No | Needed only because mounted search generates more candidates. |

Recalls:
- `HORSE_TELEPORT_RECALL` was 409 in this soak (327 in the previous run).
- 322 of them are `HORSE_RECALL_REMOUNT`, counted while the rider is "on foot": the rider is back in
  MOUNTED mode, its horse jammed more than 8 blocks back, and the 15 s timeout runs out
  (`TravellerMountSupport.java:899-932`).
- Every LED (terrain, water, melee, sidestep) ends in that phase.
- A horse that fits wherever its rider walks removes the main reason it falls behind.

## 5. Recommended design: "a mounted pair is never bigger than its rider"

1. **Narrow the bonded horse.**
   - On every bond path (`applyBondInvariants`), set the horse's size to `min rider width` × 1.6,
     for example 0.6 × 1.6. Use `EntityAgeable.setSize` through an accessor, so `setScaleForAge` keeps it.
   - Restore 1.3964844 × 1.6 when the bond is released.
   - The client resizes from the bond flag that `MixinEntityHorse` already syncs.
   - `getMountedYOffset` depends only on height, so the seat does not move.
2. **The rider plans and moves as itself.**
   - A traveller is 0.7 × 2.05 with `stepHeight` 1.0 (`EntityTraveller.java:431,1787`).
   - Its planner demands 3 free cells: `floor(2.05 + 1)`.
   - That covers the horse's 2.6 step envelope (3.1) and the rider's head on the horse:
     1.2 + 1.6 × scale = 2.8 at normal scale, under 3 cells up to scale 1.12.
   - Delete the `PathBody` switch everywhere. Mounted riding becomes on-foot riding at horse speed.
   - Keep `TravellerMoveHelper`'s turn cap and slow-for-turn for looks; drop the jump yaw freeze.
3. **Leading.** The existing trail follower stays and now always fits the trail. The horse's own A* stays
   only as the last-resort fallback. The triggers that remain are the user's own decisions: water, melee,
   wounded, village, boat.
4. **Placement.**
   - The mounted footprint becomes the rider footprint plus the 3-cell height that is already required.
   - `findClearHorseFootprint` and `findClearMountFootprint` merge into the rider placement.

**What gets deleted:**
- `PathBody`
- `MountTerrainProfile`
- The mounted branches of `TravellerNodeProcessor`: `getStart` override, `findMountedWalkOptions`,
  `bodyCollidesWithTerrain`, `addMountHopCandidates`, `classifyStackClearance`
- Mounted shortcut / `isBodyCorridorClear` / `headingAllowsCorridorShortcut` / the corridor in `steerDirect` /
  `reachesTarget(ignoreMount)`
- `tryMountedDirectRide`, `considerTerrainDismount`, `reportMountedPathFailure`
- `trySidestepIfWedged`, `tickMountedJump` and the jump math, `isMountWedged`, the terrain-fallback remount
- `MixinPathFinder`
- The wedge branch in `EntityAIFollowLeader` and the corridor dependency of `MountedFollowPacer`
- Config keys:
  - Direct ride and corridor: `mountedDirectLegBlocks`, `mountedWaypointReachRadius`,
    `mountedCorridorShortcutEnabled`, `mountedLookaheadBlocks`, `mountedCorridorTurnGate*`,
    `mountedMaxStraightDrop/Rise`
  - Jumps: `mountedJump*`, `mountedReactiveJumpEnabled`
  - Sidestep: `mountedSidestep*`
  - Rider band and planner: `mountLowHeadroomMalus`, `pathfinderNodeBudget`
  - Terrain dismount: `terrainDismountEnabled`, `terrainRemount*`
- Anomaly kinds `MOUNT_SIDESTEP`, `LedReason.TERRAIN`, `LedReason.SIDESTEP_GAVE_UP`

**What stays:** the whole bond and identity layer (one live copy, generations, load handler, unload as
data, theft guard, death), modes, water, melee and village LED, hitching, speed mirroring, and the
virtual-pace multiplier.

**Decisions for the user (this reverses parts of memory `project_horse_mounts_pathfinding`):**
- (a) The horse model (1.4 wide) visibly overlaps trunks by up to 0.4 block per side when it rides through
  a 1-block gap. If that bothers the user, a soft A* cost for 1-wide gaps is a cheap add-on later. It is
  never a refusal.
- (b) "A rider moves like a player: straight lines, jumps": no more 2-block ledge jumps, and no direct ride.
  The evidence above shows both cost more stuck riders than they save. Jumps could come back later as an
  isolated feature on top of a working base.
- (c) Players' attack box on bonded horses gets narrower.

## 6. Alternatives (not recommended)

- **A. Keep the 1.4 horse and patch the model.** Add the takeoff headroom to `fitFootprint`/`corridorClear`,
  stop treating leaves as ground, add lateral margin, add the turn gate to `steerDirect`, and fix the two
  layer conflicts in 3.4.
  - Why not: it adds a fix per symptom and keeps two clearance worlds (rider vs horse). Led horses keep
    jamming, so recalls stay.
  - A wider margin brings back the "low woodland unpathable" problem the code itself documents
    (`TravellerNodeProcessor.java:85-92`: 43 % of a route with no path).
- **B. Dismount in dense canopy, as in villages.** It moves the problem to the led 1.4 horse, which jams in
  the same trees (`LED_HORSE_JAMMED` 210 per run), and then gets recalled in view.

## 7. Migration steps

1. **Instruments first.**
   - Put the touched block and the obstacle class (wood / leaves / terrain / overhead-at-step) in the
     sidestep, wedge and stall trace events, and block names in the terrain snapshot.
   - Add an autotest scenario that spawns mounted travellers between two points in a dense forest
     (e.g. (-1888, -96) or (-1504, 224)) and counts arrivals, time and wedge ticks.
   - The soak zones alone do not exercise forests.
2. **Fix the two layer conflicts in 3.4 as a stop-gap only if** the redesign is delayed. Otherwise skip
   them, since step 4 deletes both.
3. **Narrow the horse** (5.1). `ab` against the current master.
4. **Drop the `PathBody` switch** (5.2). `ab`.
5. **Delete** the mounted planner, corridors, direct ride, jumps, sidestep, terrain LED, and their config,
   trace and anomaly entries. `ab`. Update the README: horses no longer leap ledges.
6. **Merge mounted placement** into rider placement (5.4).
7. Rewrite `mount/CLAUDE.md` "Movement" and `pathing/CLAUDE.md` (one clearance model = the rider's own),
   and update the memory note.

## 8. How to measure success

Each step gets a 10-min `servertest.py soak` with the same zones, plus the forest scenario. Then `compare`.

Before, this soak (c850cd73 + local changes, 476 units, forest zones added), with the previous run in
brackets:

| Measure | Before | Target |
|---|---|---|
| Mounted stall episodes | 42 in the previous run | down |
| Stall-time share | 0.7 % | down |
| Travel efficiency, mounted | 0.63 [0.76] | ≥ on foot (0.70) |
| Travel efficiency, leading a horse | 0.49 [0.58] | up |
| `MOUNT_SIDESTEP` | 120 [100] | gone by construction |
| Terrain LEDs | 230 [165] | gone by construction |
| Sidestep give-up LEDs | 55 [41] | gone by construction |
| `LED_HORSE_JAMMED` | 210 [252] | large drop |
| `HORSE_TELEPORT_RECALL` | 409 [327] | large drop |
| `HORSE_RECALL_REMOUNT` | 322 [310] | large drop |
| `HORSE_RECALL_LED` | 87 [17] | large drop |
| A* time | 8.07 ms/tick (inflated by my extra zones) [2.0] | not worse |

- The forest scenario must show near-100 % arrival with no wedge longer than 2 s.
- The flicker signature (`fwd` alternating with `hcoll=1`) must disappear from auto-traces.

## Side notes (outside the task)

- The previous long-running server log (9 h) had **216 "Keeping entity minecraft:horse that already exists"**
  warnings; this 10-min run had 1. That looks like a duplicate horse body, against the "one live copy"
  invariant. Worth a look by whoever handles the duplicate-body item in `docs/automated-server-tests-plan.md`.
- Melee LED is the largest LED source (376-456 per run, only about half remounted). That comes from the
  user's "A+B" decision. The redesign does not change it, but it feeds the remount-recall count.
