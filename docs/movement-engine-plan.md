# Movement engine plan — steer first, path last

**Status: step 1 in progress — regressions found by traces fixed (followers against fences/branches, path-block step); waiting for a profile; open: too-far drop loop (needs distance probe), horse recall loop, follower ground-goal cost, then decide on the single engine class.**

## Goal

Several players with their henchmen on one server at an acceptable TPS, on any terrain and in combat. Units move by cheap means first; a real A* search becomes the exception, so per-AI budgets turn into a fuse that almost never trips instead of the thing that shapes behaviour.

## Decisions taken with the user

- **One movement authority.** An AI says "go there"; the engine escalates, cheapest first:
  1. **Direct**: straight line, 1-block steps (`stepHeight` 1.0), small drops, simple obstacle deflection.
  2. **Borrow**: reuse a known route (leader's / player's trail, a groupmate's recent path toward the same area). In a group, one unit thinks, the others borrow.
  3. **Short detour**: a small A* (about 16 blocks, small node budget) only to get past the obstacle, then back to direct.
  4. **Full search**: only after 1-3 failed.
- Budgets stay as a fuse (`PathBudget`), not as the regulator.
- Trade-off: balance. About 1 s of hesitation at a real obstacle is fine; a unit that stays stuck is not.
- Archers keep `rangedMaxDist` 128 on the user's server; the engine must make long-range archery cheap, not shorter.
- Off-thread pathfinding: not now. Decide on numbers after v1 (needs a block snapshot and our own node evaluator).
- v1 = steps 0-2, with travel (and so catch-up) first: it was half of all A* time. Other AIs (village visit, camp, stable, fishing, led horses) come after v1.
- A block change never triggers a re-search (vanilla's `PathWorldListener` repath is a no-op in our navigator).
- Tier 1 turns a refused straight leg 30° then 60° either side before A* (`TravellerNavigateGround.steerAround`).
- A mob dropped as too far is ignored 5 s by that unit (breaks the pick/drop loop); a hit always lifts any ignore. Not enough: "too far" drops did not fall; the dropped targets are probably long-range ones — needs distance in the probe.
- On-foot follow steering goes through the corridor test + turn-aside like travel (it used to report every tick as a success, so a follower against a fence never escalated); a drifted path is dropped only when the straight leg is clear.
- The corridor test measures a step from the real surface (a path block to a full block is 1.06, not 1.0).

## Already done (before the engine)

- Path searches share one blank chunk for unloaded chunks (was the first Spark hot spot).
- Follow searches capped at 16 per tick.
- Followers: a drifted path is dropped for steering instead of re-searched; steering waits 1 s blocked before asking A*.

## Steps

0. **Measure** — `/travellersdebug profile` keys A* rows by the code that really asked (two stack frames, read only while profiling). Status: done (baseline `profile_20261027_110536`).
1. **Engine + travel + follow + combat** — done so far: travel goes direct first for every unit (`TravelPathDriver.tryDirect`, was mounted only, and every waypoint switch used to search first); archers never search (straight steering to target or peek spot); melee waits 1 s blocked like follow; block-change repaths removed; profile counts "searches avoided by going direct" and keys "too far" drops by who gave the target. Remaining: the engine in `pathing/` built from the existing steering (`FollowSteering`, `steerDirectLegCapped`) and navigator; follow, melee and defend use it. Also: combat target churn (adopt → "too far" → drop) and per-tick ground-goal recomputation for followers. Status: in progress.
2. **Sharing** — one thinker per group; followers borrow its trail (`FollowRouteCache`, `PlayerLeaderTrail` generalised). Status: todo.
3. *(after v1)* Other AIs move to the engine.
4. *(after v1, only if numbers say so)* Off-thread search.
