# Combat steering plan

**Status: approved by the user (shared class, combat-specific constant, budget stays 8). Steps 1-2 done (build green), step 3 (in-game test) next.**

## Goal
Melee fighters walk straight at their target (with obstacle avoidance) like followers do, and ask for an A* path only when steering is blocked. A denied path request must never leave a fighter standing still.

## Why
`EntityAIAttackMeleeCustom.updateTask` (lines ~277-291) is path-first: no budget granted and no live path = no movement. With the combat budget at 8 per tick and 100+ fighters, units freeze.

## Findings (FollowSteering)
- Hazard probes, deflection, gap jump and `steerTo` only need the unit (`follower`) and a `waterIsSafe` flag. They do not need the follow task.
- Follow-specific parts: `tryFollowSharedCorridor` (groupmate routes), `tryFollowLeaderTrail`, `idleAtSlot`, `isLeaderSwimming`, the `ai.stuck` progress signal, slot-stop distances.

## Product goal (user)
Units stay as reactive and functional as possible whatever their number or the number of groups: stable performance in the widest range of situations. So steering is the default (cost per unit is flat), A* is a rare, budgeted fallback.

## Requirement from the user
Fighters must not walk into lava, big holes, water or off cliffs, and should avoid silly situations in general: the shared class carries all the hazard probes, not only obstacle deflection.

## Proposed design
1. **Extract** the unit-only part of `FollowSteering` into a shared class `follow/DirectSteering` (hazard probes, deflection with its cached direction, gap jump, `steerTo` incl. mounted `steerDirectLegCapped`, `steeringBlockedTicks`). `FollowSteering` keeps the follow-only parts and calls it. Behaviour of follow must stay identical.
2. **Combat uses it**: out of reach, the fighter steers at the target every tick. If `steeringBlockedTicks` passes a threshold, it asks `PathBudget` (COMBAT) for a path; while a path is live the navigator drives, as today.
3. **Denied = keep steering**, never stand still.
4. Mounted fighters: first pass keeps today's path-first logic; moved to steering in a second step after a test.

## Decisions to take with the user
- Extract a shared class (recommended) vs copy the code into combat.
- Threshold before asking for a path: reuse the follow value (`steeringStuckTicksBeforePathfind`) vs a named constant for combat.
- Keep combat budget at 8 (recommended).

## Steps
1. Extract `DirectSteering` from `FollowSteering`, no behaviour change — build, unit tests. *done*
2. Wire melee to steering-first, path on block. *done* (on-foot only; constants: 10 ticks blocked before a path, 40 ticks retry backoff, path kept 60 ticks or until target moves 4 blocks)
3. 10-min raid test with `/travellersdebug profile`: frozen units, A* ms per tick. *todo*
4. Mounted fighters. *todo*
