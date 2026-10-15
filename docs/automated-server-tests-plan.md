    # Automated server tests — Claude builds, runs and reads in-game tests itself

**Status (2026-10-09)**: steps 1-4 done, bugs 1, 2, 4 fixed; step 5 written and compiled, NEVER RUN (the test server copy and snapshot no longer exist; the user will recreate them later, so `setup` + a fresh snapshot + a new log baseline come first, then a first `servertest.py scenarios` to debug the scenarios themselves).

Test world = the user's `LGV2026_srv/world` as of 2026-10-05 (6 villages, 219 hired workers, most at
(-1600, -320)), zipped as the copy's `.autotest/snapshot.zip`; the 2026-10-02 one is kept beside it.

## Goal

Replace most manual in-game testing with tests Claude runs on its own (under the user's supervision): build the
mod, deploy it to a local test server, start it, drive it with commands, read compact reports, stop it. The user
keeps only what needs eyes (natural movement, gaze, voices, animations, screens) — a short list, not sessions.

Context budget is a design constraint: Claude never reads `latest.log` or a trace directly. Every tool answers
in a few lines and points to a file for details.

## Facts this plan relies on (verified 2026-10-05)

- Test server: `/home/user/Desktop/LGV2026_srv` — Cleanroom 0.6.13 on Java 25 (`settings.cfg`), ~150 mods,
  `view-distance=8`, `online-mode=false`, `enable-rcon=false`. Start to `Done` ≈ 66 s. The world is disposable
  (user); a snapshot `world-20261002-160520.zip` sits next to it.
- A normal start already logs ~230 exceptions from other mods: a log scan must compare against a baseline,
  never alert on "any exception".
- Vanilla 1.12 (`WorldServer.updateEntities`): with no player and no forced chunk, entity ticking stops after
  300 ticks. Our offscreen simulation is paused on an empty server by default
  (`OFFSCREEN.pauseWhenServerEmpty`): the runner forces it off in the copy's config before every start.
- RCON runs commands on its own thread; `/travellersdebug` hops to the server thread and waits.
- A Forge ticket changes vanilla's tick gate: an entity in a ticketed chunk ticks with a 0-block margin instead
  of 32 (`VirtualTravellerManager.isFrozenByVanilla` mirrors this). Ticketed test zones therefore behave like
  a player's area, except right at their border.
- `TravellerChunkManager` releases any of our tickets restored from a save on load — test zones must be
  re-created each run (that is what we want anyway).
- Existing oracles to reuse: movement profiler (`MovementAnomaly` counters, `/travellersdebug profile`),
  per-unit trace with automatic incident capture, path test harness (`debug/path`).

## Decisions taken with the user

- Chunk loading during tests is free: the test world is disposable and tests may do whatever they need.
  (The mod's own rule is unchanged: load a chunk only when needed, never for nothing such as path probes.)
- Plan in `docs/` rather than a prompt for a fresh agent.
- Tests run on a sibling copy `/home/user/Desktop/LGV2026_autotest` (own ports, own frozen jar), never on
  `LGV2026_srv`.
- The world is restored from a snapshot before each run (comparable runs).
- The test server may run while the user's server/client run; the runner refuses to start when free memory is
  too low and reports that timings may be skewed by a loaded machine.
- Owner-dependent behaviour (follow the player, assist the owner, notices) stays on the manual list; a
  server-side fake player may be considered later.
- The test server is never left running: `forest`, `soak` and `ab` stop it when they finish, and Claude stops
  it after any manual `start` (user, 2026-10-06). Verbs restart it whenever needed, without warning.
- A player's presence is marked, never excluded: soaks record `player_minutes`, `compare` warns when only
  one side had a player, and a bad placement within view distance of a player is tagged "near a player".

## Design

### Runner (outside the game) — `tools/servertest/` — status: todo

One script (`tools/servertest/servertest.py`), one verb per call, each printing ≤ 10 lines:
- `setup` — one-time copy of `LGV2026_srv` (without world, logs, `config/flare`) with its own ports and RCON.
- `deploy` — copy `build/libs/travellers-latest.jar` into the server's `mods/` (`./gradlew build`'s own
  `deployToServer` targets the remote server and is ignored).
- `reset` — restore the world from the snapshot.
- `start` — launch in a detached `tmux` session; wait for `Done (` in the log with a timeout; print the time.
- `cmd "<command>"` — send one console command through RCON (enabled with a local password) and print its reply.
- `wait <seconds>` / `stop` — `stop` saves, waits for the process to exit, kills after a timeout.
- `logscan` — new problems only: exceptions and errors whose stack or logger mentions `com.jubitus`, mixin
  failures, and any exception signature absent from a stored baseline. Deduplicated, ≤ 30 lines, full log path.
- `ab <commitA> <commitB>` (step 4) — build both commits in a git worktree, run the same soak on each, diff.
- `forest [seconds]` — mounted forest crossing scenario (`/travellersdebug autotest forest`) on 3 fixed routes;
  `ab` runs it before each soak. Summaries in `.autotest/forests/`.

### In-game harness — `debug/autotest/`, under `/travellersdebug autotest` — status: todo

- `zone add <x> <z> <radiusChunks>` / `zone clear` — Forge tickets standing in for players (named spots:
  a village, a road between two villages, a Barrack/Stable built in the snapshot).
- `invariants` — one pass over loaded entities and records: loaded XOR virtual (no unit both, no hired worker
  vanished without dying), no NaN/void position, every hired worker record has an owner, counts. A placement is a FAIL only
  inside a wall (body in solid blocks) or on a Millénaire roof (inside a footprint, under open sky, above its
  ground floor, with air under the supporting block — fountains, courtyards and terraces are not roofs) — user rule: being inside a building is fine; a floor with no way out is not checked for now.
  Each failure names the mod code path that placed the unit. Watchers only run under `-Dtravellers.autotest=true`.
- `soak <minutes>` — zones + profiler + periodic invariants; writes one report.
- `run <scenario|all>` — scripted scenarios (below); each has setup, a tick budget and an outcome check.
- Every command answers in one line and writes `logs/travellers-autotest/<run>.txt`: one line per check,
  `PASS`/`FAIL reason`, and the trace file of the unit involved when it failed.

### Scenarios (first batch) — status: todo

Outcome checks only ("arrived within N s", "has a horse"), never exact paths — AI randomness and path budgets
make paths vary between runs. A failure re-runs once before being reported, to separate flaky from broken.
- Worker Go order across open terrain; same with a forced virtualize/materialize mid-way; same with
  save + restart mid-way (save keys).
- Get Equipment at a Barrack (stay / come back) and at a Stable (gets a horse).
- Go home / go to duty.
- Recruit spawn: the new worker is not inside a building nor on leaves.
- Catchup: a worker group whose leader is teleported 80 blocks away regroups.
- Raider vs workers: the fight starts and ends; raiders spawn with their configured health.
- Boat landing: the unit lands on a dry shore outside buildings.

## Steps

1. Runner: `deploy`, `start`, `cmd` (RCON), `stop`, `logscan` with a baseline taken on the current jar.
   This alone is the startup smoke test (dedicated-server crash, mixin not applied, save read errors).
   — done: full cycle verified twice; baseline of 69 known signatures stored in `LGV2026_autotest/.autotest/`.
   The test server needs ~7.5 GB free: it cannot run alongside the user's server + client on this machine.
2. Harness skeleton: `autotest zone`, `invariants`, report file. Smoke test = start, zones, 3 min, invariants,
   stop, logscan. — done. Zones used: villages Monzon (-1021, -967) and Abacı-Gölde (-1325, -251), radius 3.
   Open: one run caught 2 travellers placed inside a building near (-1277, 66, -202) before the placer was
   recorded; not reproduced in two later runs.
3. `soak` + compact profile summary; baseline numbers for the current jar. — done. Driven by the runner
   (`soak [minutes]`, default 10 (user decision), zones = the 6 villages + the user's worker base + the Monzon/Abacı-Gölde
   midpoint, user decision); the profiler also writes a flat `.metrics` file next to its report; summaries are kept in
   `LGV2026_autotest/.autotest/soaks/`. Runner keeps one RCON connection and waits until `/travellersdebug`
   answers before declaring the server up.
   Baseline (jar 0517bb4d, 20 min): ~175 units loaded, 989 materialized, 43 stalls (longest 527 s),
   travel efficiency on foot 0.71 / mounted 0.74 / leading horse 0.57, A* 2.0 ms/tick.
   Found, not fixed yet: units materialized inside tree leaves (`leaves2`, by `materializeEntity`); vanilla warns
   "Tried to add entity travellers:traveller_raider but it was marked as removed already".
   The 8 "inside a building" placements it reported were legitimate (village visitors at Millénaire spots):
   the placement rule was replaced, see Design.
4. `ab` comparison between two commits. — built: `compare <a> <b>` and `ab <commitA> <commitB> [minutes]`
   (git worktrees in `.autotest/worktrees/`, `libs/` symlinked since it is git-ignored; works for commits that
   have the autotest harness and profile metrics, i.e. from fbe72570 on).
   Noise (same jar twice, 20 min, user's world): travel efficiency within 4 %, A* cost +21 %, incident rates
   16-69 % apart, longest stall 168 vs 740 s. Thresholds (user decision: wide + a second run on alert):
   efficiency -10 %, incident rate x2.5 with at least 20 cases, A* cost x1.5, a new invariant failure kind.
   Same-jar `compare` → no alert.
   Bugs reproduced in both noise runs, not fixed: units materialized inside terrain/leaves (stone, sand,
   leaves2, fence…); vanilla refuses a duplicate traveller/raider body ("Keeping entity … already exists");
   raider spawned while already removed.
   "Hired worker vanished without dying" (4fd0dbe1…, 9c94011b…) is a false alarm of the check, not a game bug:
   the two are pending bed respawns at the user's Stable1 (-2078, -425) in the snapshot, come back at the
   first morning (~9 min into a run), and die in a virtual battle against raiders camped there a minute
   later; a virtual death removes the record without a `LivingDeathEvent`, which is all the check listens to.
   Reproduced in 3 min with `time add 10900`. Fixed in the check (c1e64537).
   Placement failures now name the placement steps (`via resolve>canopyEscape…`). Cause of units in
   terrain/leaves found: 4 calls in `MaterializePositionResolver` pass block coordinates to
   `LoadedChunks.isLoaded(world, int chunkX, int chunkZ)` (lines ~303, 356, 504, 699), so they always read
   "not loaded": natural-ground search, canopy escape, footprint push-out, mount footprint and the
   `ensureSpawnable` Y re-resolve never run. Fixed by renaming the chunk overload `isChunkLoaded`.
   Soaks now default to 10 min (user: dense population, short runs are enough) and sample tick time;
   without a player the server runs ~34 ms/tick (20 TPS, worst minute 41 ms).

## Bug order (user decision, 2026-10-05)

1. Vanished hired workers — done (false alarm of the check).
2. Units materialized inside terrain/leaves — done (13973907): `ab` 10 min, 8 bad placements -> 0, no alert
   on a clean second run (the first B run had the user online and lagging).
3. Duplicate body refused by vanilla — not reproduced in a 10-min run; `AutotestChunkBodies` now reports one
   with the circumstances of the save that wrote it. Suspected same family as 4 (rider and horse registered
   in different chunks).
4. Raider "marked as removed already" — cause found: vanilla kills a body read from a chunk file when its
   position lies outside that chunk; a rider is saved inside its horse's chunk while its own position can
   cross the border (a rearing horse shifts it up to 0.7 block; a restored horse left its rider beside it
   until its first tick, forever on non-ticking ground). Fix 0ed68b08 (user: HEAD inject only, as light as
   possible): `MixinChunk` moves our units just inside the chunk instead; a restored horse seats its rider
   at once. `ab` running.
Each fix is validated by an `ab` before/after.
Diagnostic audit (user decision 2026-10-06: tools first, horse teleport recalls left to another agent):
   long stalls checked against auto-captured traces — the counter is right (3/3 real: two hired followers of
   the 51+ group at the base pushing into a wall for 5-7 min, an archer in combat walking into a wall with no
   path). Not fixed (gameplay, later). Trace start now works from the console; tests force stall auto-capture.
   Handoff for the agent fixing horse teleport recalls (user: later, another agent):
   - Before fixing: trace a few hotspot riders to see why they dismount and why the horse drifts; the
     recall is the symptom, not the cause.
   - Measure: `servertest.py soak` (10 min) then the profile's anomaly lines; `compare`/`ab` before/after.
     `HORSE_TELEPORT_RECALL` is the total (comparable with older runs), split into `HORSE_RECALL_REMOUNT`,
     `HORSE_RECALL_LED`, `HORSE_RECALL_HITCHED` and `LED_HORSE_SNAG_RECALL` (all counted in
     `TravellerMountSupport.teleportMountNear` and its callers).
   - Baseline (c850cd73, 10 min, ~420 units loaded): 327 recalls, 310 of them REMOUNT, 17 LED, 0 hitched,
     0 snag. Traced REMOUNT recalls jump 25 blocks on average (max 38): visible. Rule that fires: rider in
     MOUNTED mode on foot waiting to remount for more than `remountTimeoutTicks` (15 s) with the horse over
     8 blocks away, or the horse beyond `leadMaxDistance`.
   - Hotspots (64x64 cells): (-1248, -608) mesa_rock 92 REMOUNT, then (-1504, -1440) and (-1504, -1376)
     ~35 each, (-1056, -928) 23. A trace event `recall horse from N blocks (mode=…)` is written on traced
     units; `trace start <name>` works from the console, stalls are auto-traced in tests.
5. Scenario batch above. — written, untested. Engine `debug/autotest/scenario/` (`AutotestScenarios`, one `AutotestScenario`
   subclass per scenario; a failure is retried once on another line of ground; `autotest run <name|all> <x> <z>`).
   Runner: `scenarios [name|all]` (arena = `SCENARIO_ARENA` + the soak zones) and `restart-go` (save + restart mid-walk).
   Scenarios: `go`, `go-virtualized`, `go-restart-save`/`go-restart-resume`, `catchup`, `recruit-spawn` (checks
   `RecruitMission.findSpawnPos`, not a whole tour), `raider-fight`, `equip-barrack-stay`, `equip-barrack-return`,
   `equip-stable-return`, `go-home`, `go-duty`, `boat-landing`. Zone scenarios use the first zone of the type in the
   world and SKIP when there is none; workers are hired by a FakePlayer (the zone's owner for zone scenarios) and the
   assignment is set directly, bypassing `ZoneAssignments`' packets and beds.
   Unknowns to check at the first run: FakePlayer as hirer (any code path that needs the owner online), the
   boat crossing search finding water near the arena, the Stable having a ready horse in the new snapshot.
6. `debug/CLAUDE.md`: how to run the suite and read its reports (a few lines); the manual checklist shrinks to
   what needs eyes. — todo
