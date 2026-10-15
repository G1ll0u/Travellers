# Raids (horde events) plan

Status: steps 1-8 built and playtested in game (raiders reach the player and fight). Remaining: performance of mass fights, see `docs/raids-performance-plan.md`; then archive this plan.

## Goal

Per-player raid events: groups of raiders appear at a distance, march on the player and fight whatever they normally fight, with a stronger pull toward the player and the player's hired workers. Raids scale with the player's strength and are highly configurable.

## Decisions taken with the user

- **Engine**: raiders are created as **virtual records** on a ring around the player (configurable distance), placed in the direction of a hostile village, never inside a village. They march virtually, then materialize near the player. No change to their normal combat behaviour; only target preference leans to the player and their workers.
- **Strength**: proportional to the player. Inputs: hired workers and their equipment only (friendly villages ignored). A scale setting multiplies the result.
- **Legion of workers (100+)**: strength grows with a saturating curve, and a hard cap on raiders per raid and on active raiders overall keeps performance bounded.
- **Culture**: random among the allowed cultures. The player can include/exclude cultures, down to a single one. Reputation with Millénaire villages is a later step.
- **Loot**: out of scope, separate session.
- **Settings**: Jubiconfig only (frequency, raider count, group size, difficulty scale, time slots day/night with weights, allowed cultures, ring distance). No dedicated GUI for now.
- **Warning**: red notice with the direction, some seconds before arrival, through `NotificationService`.
- **Groups**: one raid = several groups of varied size, arriving in spaced waves.
- **Multiplayer**: a raid belongs to one player; timers and cooldowns are per player.
- **Scheduler defaults**: average 5 days, night 80 / day 20, 3 days grace; raids off by default; max raid duration 20 min (internal constant).
- **Debug**: a command to trigger a raid immediately (step 8); `/travellerChunks` population map must highlight the raiders of a raid.

- **Player with no worker**: a minimal raid, not none.
- **Player inside a village**: raiders attack anyway (no deferral).
- **Raid end**: all dead, or a maximum duration then disperse.

## Edge cases to handle

Offline player (no raid), peaceful difficulty (`TravellersDerivedConfig.isPeacefulBlocked`), other dimensions, global cap on active raid raiders, raiders must never be placed inside Millénaire buildings (`MillFootprints.isForbiddenLanding`), raid state persisted so a restart resumes it.

## Steps

1. Verify the existing machinery. — done, findings below
2. Raid state + per-player scheduler (timer, time slot, grace) in a new `raid/` folder with its own `CLAUDE.md`. — done (no raider yet: a due raid only becomes the player's active raid)
3. Strength formula (pure logic, JUnit tests). — done
4. Spawn ring + group/wave assembly, culture filter. — done
5. Target preference toward the player and workers (reuse `combat/` rules). — done
6. Notification + config fields + `en_us.lang` keys. — done
7. Population map highlight of raid raiders. — done
8. Test command to start a raid at once, without waiting for the timer (user request). — done

## Step 1 findings (verified in code)

- **Creation**: `TravellerSoftSpawner.spawnVirtualTraveller` builds a temp `EntityTravellerRaider`, writes its NBT into a `VirtualTravellerRecord` (`entityClass = "RAIDER"`, `homeVillage`, `cultureId`, group leader/followers via `groupLeaderUUID`, shared `mounted` and `groupFormationStyle`), then `mgr.addRecord`. A raid reuses this path with its own spawn position (ring) instead of the village ring.
- **Home village is mandatory today**: every record has a `homeVillage` (used by quota counting and destination choice). Raid raiders must not count toward a village's raider quota, or the soft spawner will refill them.
- **Destination**: a record walks `tripStops`; `tickRoamRoute` ends a leg with `advanceTrip`, which picks a new village destination. A raid needs a leg whose stop is the player's current position, re-aimed while the raid lasts, and no new roam trip, camp (`handleCamp`) or village visit (`visits.tryStart`) while raiding. Followers already track their leader (`refreshGoal`).
- **Loaded side**: raiders walk the same trip through `aiTravel`; combat is `RaiderAttackTargetAI` (nearest of player / cross-faction unit / attack-list entity). "Lean toward the player and workers" means a priority bias in that single target task; `EntityTravellerRaider.initEntityAI` is hand-mirrored, so any change goes there.
- **Population map**: `PopulationMapStream.collectAll` builds one `Entry` per loaded unit and per record with `Kind` TRAVELLER/RAIDER/WORKER/STABLE; the message encodes the kind as one byte. The highlight needs a raid flag on the entry (or a new `Kind`) plus client drawing.
- **New persistent state**: a `raidId` on `VirtualTravellerRecord` and on the entity, captured/restored per the root checklist (`VirtualSnapshot`, reset at materialize).
- **Open design point for step 2**: where raid state lives (new `WorldSavedData` per player: next-raid timer, active raid id, member ids, target player UUID).

## Built (steps 3-8)

- `RaidStrength` (pure): worker power = damage x effective health, relative to a bare worker; raiders = 3 + 2.5 x sqrt(sum), times the scale, capped by `maxRaiders`.
- `RaidComposition` (pure): warbands of 4-10; arrival is staggered by spreading them over 80 extra blocks of distance and +-35 degrees toward the home village.
- `RaidLauncher`: culture picked among allowed cultures present in the world; home = nearest village of that culture; notice is the red `RAID_INCOMING` toast with a place.
- Membership lives in `RaidWorldData` (unit uuid -> raid id). Guards on that lookup: camp (loaded + virtual), village visit (loaded + virtual), new roam trip (virtual), `ensureRoutePlanned`, village quotas (loaded + virtual).
- A raid ends when all members are gone or after 20 min; survivors are then released as ordinary raiders.
- Target bias: `RaiderAttackTargetAI` picks the raid's player or their hired workers first.
- Map: new `Kind.RAID_RAIDER`, magenta raider icon, counted as raiders in the panel.
- Debug: `/travellersdebug raid now|status|end`.
