# Raids performance plan

Status: idea 1 done (raid scan nearest-first, 8 rays max, vanilla 1-in-10 gate kept; counters in `/travellersdebug profile`) and idea 6 (server-wide raid cap). Next: a new spark report of the 200 v 170 fight; ideas 2-5 only if it still shows a hot spot.

## Goal

Drastically lower the server cost of a mass fight (measured: 200 hired workers vs 170+ raiders) without any loss of combat quality (targets, reactions, speed of engagement).

## What the spark report showed (server thread = 100%)

- `RaiderAttackTargetAI.nearestRaidTarget()`: **~16%** of the whole server thread (8.07% + 7.86% on mounted units), of which `EntitySenses.canSee` (ray-trace through `World.rayTraceBlocks`, hooked by fluidlogged_api) is **~14%**. This is the raid target-bias code added with the raids.
- `EntityAIFollowLeader.updateTask()`: ~9% (4.45% + 4.51%).
- `EntityAITravel.updateTask()`: ~4%; `EntityTravellerWorker.onLivingUpdate()` 34% total (children not expanded in the report); `EntityHorse.onUpdate()` 5.9%; `MillVillager.onUpdate()` 2%.
- Everything else under `EntityAITasks.onUpdateTasks()` is small (< 1% each).

## Verified causes in `RaiderAttackTargetAI.shouldExecute` (my raid bias)

1. **It bypasses vanilla's 1-in-10 scan gate** (`EntityAINearestAttackableTarget.targetChance`, `rand.nextInt(10) != 0`). The vanilla path stays under 0.1%; the bias runs on every call the `PathBudget` (`COMBAT_SCAN`) lets through, about 10x more often.
2. **It ray-traces every candidate before comparing distances.** The candidates are the player plus *all* of the owner's hired workers inside follow range (64 blocks): up to 200 `canSee` calls per scan. The nearest visible one is all that is needed.
3. The AABB scan returns every living entity in a 128 x 128 box (workers, raiders, horses, villagers) and filters afterwards.

## Ideas, ranked by expected gain per effort (to be validated by measurement)

1. **Gate and order.** Apply the same 1-in-N gate as vanilla; test the player first (one ray), then candidates nearest-first and stop at the first visible one; cap the rays per scan. Expected to remove most of the 14%.
2. **Cache line of sight** per (raider, target) for ~10 ticks; skip it entirely inside a short distance where a hit is imminent anyway.
3. **One scan per raid, not per raider.** The raid already knows its player and its group; a per-owner list of hired workers (spatial hash rebuilt every 10 ticks) lets each raider ask "nearest 3 preferred targets" in O(1). Cost becomes O(R + W) instead of O(R x W).
4. **AI level of detail.** Loaded units far from every player (> ~48 blocks) run goal selection every 2-4 ticks, staggered by entity id; they are walking, not deciding.
5. **`EntityAIFollowLeader` throttling**: re-path a follower only when its leader moved more than a few blocks or every few ticks; expand its self-time in a new report first.
6. **Mass-fight cap**: a limit on raid raiders alive at once, and the existing attacker cap, checked with the 200 v 170 scenario.

## Method (build the instrument first)

- Add counters (calls per tick, candidates examined, ray-traces done) to `MovementProfiler` / `/travellersdebug profile` for the raid scan, so every idea has a before/after number.
- Reproducible scenario: `/travellersdebug battle spawnfight` or an autotest with 200 workers vs 170 raiders; capture a spark report with the **worker `onLivingUpdate` children expanded**.
- Keep combat quality: compare time-to-first-engagement and kills per minute before and after, not only milliseconds.

## Decisions to settle with the user

- Whether raiders may "know" where their raid targets are beyond line of sight (cheaper, slightly more aggressive) or must keep seeing them.
- A lower default `maxRaiders` or a global cap on active raid raiders.
