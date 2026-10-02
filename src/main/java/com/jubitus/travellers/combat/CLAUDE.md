# Combat AI and hostility

AI tasks here; event handlers, hostility rules and cross-mod compat in `combat/`.

## Targeting
- **Every `targetTasks` entry uses `setMutexBits(1)`** — that bit arbitrates between target selectors.
- **One scan per entity**: `RaiderAttackTargetAI` / the worker's `ThrottledAttackTargetAI` each use one `EntityAINearestAttackableTarget` with one predicate. New target categories go into the predicate, never a parallel task.
- `shouldAttackExtraEntity()` is the single place deciding "extra" hostile categories.
- Every AABB-scanning `shouldExecute()` acquires `CombatTargetScanScheduler.tryAcquire()` first (world-wide budget).
- `ForeignTargetTaskScrubber` strips target tasks other mods inject directly.
- Peaceful: always `TravellersDerivedConfig.isPeacefulBlocked(world)`, never `getDifficulty()` directly.
- Attack lists per type: `ATTACK_RULES` → `RAIDER_/TRAVELLER_/WORKER_ATTACK_LIST` (worker falls back to traveller).
- Workers prefer a target near their owner/leader (`COMBAT.escortOwnerPriorityRadius`).

## Retarget and attacker cap
- `EntityTraveller.requestImmediateRetarget()` after a kill is a hold-off window; it fires **once per death** (`lastHandledDeadTarget`) or the unit stays "in combat" forever.
- The melee-attacker cap uses `computeEffectiveAttackerCap()` at both call sites (join and per-tick refresh).

## Melee
- Mounted fighters dismount to LED on reaching strike range and curve their approach while closing (see `mount/CLAUDE.md`).
- Never apply durability wear after a call that may have killed the entity (shared `ItemStack` with the drop) — guard on health first.

## Ranged bow (`EntityAIRangedBowTravellerSafe`)
- Shared by all three types. The bow stays drawn the whole time the task runs, LOS or not (deliberate). Drawn state only through `EntityTraveller.setAimingBow()`.
- Cooldown ticks regardless of LOS; only firing is LOS-gated. Distance-keeping aims at a preferred band. Repositioning checks are throttled. HOLD attitude disables blind-pursuit repositioning.

## Hostility rules (`combat/`)
- Hired-worker hostility toward players: see `PlayerEnemyRelations` (account-wide) vs `PlayerHostility.localHostilePlayers` (per-worker transient).
- Proactive aggression is suppressed while `campingPassive` (camping, on duty); retaliation is never suppressed.
