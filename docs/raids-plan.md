# Raids plan

Status: phase 2 coded (steps 1-6), unit tests green; horn and end sound heard in game; rest of the in-game check pending (step 7).

## Goal

Finish raids: real waves, raids that differ from each other, a raid the player cannot miss (start, progress, end), everything configurable. Then archive this plan and `raids-performance-plan.md`.

## Already built (v1)

Per-player scheduler (`RaidScheduler`, `RaidSchedule`), state in `RaidWorldData`, strength from hired workers (`RaidStrength`), warbands placed as virtual records on a ring toward the home village (`RaidLauncher`, `TravellerSoftSpawner.spawnRaidGroup`), target bias toward the player and their workers (`RaiderAttackTargetAI`), red toast at launch, map highlight, `/travellersdebug raid now|status|end`. Raid scan already nearest-first with 8 rays max (`b1ba3f69`).

## Decisions taken with the user

- **Strength**: only hired workers within a radius of the player count (config, 128, 0 = all of them).
- **Spawn distance**: pushed automatically just beyond the server view distance when the config is shorter (spawns inside it are vetoed).
- **Global cap**: config, 160 raid raiders alive server-wide; a wave that would exceed it is shrunk; nothing left = the wave waits.
- **Waves**: number rolled between a config min and max, rising with the raid's total size (henchmen strength and difficulty scale). Total raiders shared between waves, growing (last wave hardest); `maxRaiders` caps the whole raid. Next wave when ≤ 25 % of the current one still stands, or after a config delay. The 20-minute limit applies per wave.
- **Diversity** (all with config weights):
  - **Approach** per raid: one front, two fronts, encirclement (each wave from another direction).
  - **Wave kind** per wave: Horde (more, light gear), Elite (fewer, heavy armour), Cavalry (all mounted), Archers (bows). Same strength budget; gear via `SummonLoadout`, generalised to raiders.
  - **Mixed cultures**: a later wave may come from another allowed culture.
- **Visibility**: vanilla boss bar for the whole raid ("Raid — wave 2/3", gauge of the current wave still standing); toast at start, at each new wave, at the end ("Raid repelled." / "Raid over: the raiders scatter."). Toasts already show direction and distance through `NoticePlace`.
- **Sounds**: user's own files in `sounds/raid/horn|end/`. Horn at start and each wave, played 6 blocks toward the wave at full volume (24 blocks was too faint), heard only by the raid's player; end sound on a repelled raid.
- **Defaults** (agreed): waves 1-4, max delay 5 min, approach 50/30/20, kinds 40/20/20/20, mixed culture 25 %, strength radius 128, server cap 160.
- **Out of scope**: village reputation, loot, the battle live toast (separate issue).

## Steps

1. Fixes: raid scan keeps vanilla's 1-in-10 gate; "no raid on login" checked at each login; drop unused `forgetMember`. — done
2. Strength radius, spawn distance beyond view distance, global cap. — done
3. Wave engine: pure `RaidPlan` (wave count, sizes, kinds, approach, cultures; JUnit), stored in `RaidWorldData`, waves launched by `RaidScheduler`. — done
4. Wave kinds: `SummonLoadout` for raiders, kind applied at spawn. — done
5. Visibility: boss bar, toasts, horn/end sound events from `sounds/raid/horn|end/*.ogg` (registered only when clips exist), spatialized horn. — done
6. Config fields, `en_us.lang`, README. — done
7. In-game check + spark re-measure by the user; archive both plans. — todo
