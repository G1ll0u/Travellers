# Combat AI and hostility

AI tasks here; event handlers, hostility rules and cross-mod compat in `combat/`.

## Targeting
- **Every `targetTasks` entry uses `setMutexBits(1)`** — that bit arbitrates between target selectors.
- **One scan per entity**: `RaiderAttackTargetAI` / the worker's `ThrottledAttackTargetAI` each use one `EntityAINearestAttackableTarget` with one predicate. New target categories go into the predicate, never a parallel task.
- `shouldAttackExtraEntity()` is the single place deciding "extra" hostile categories.
- Every AABB-scanning `shouldExecute()` acquires `PathBudget` (`COMBAT_SCAN` row) first. Who is an enemy: `Hostility` (`isCrossFaction`, `isWorkerEnemy`), never a local copy.
- `ForeignTargetTaskScrubber` strips target tasks other mods inject directly.
- Peaceful: always `TravellersDerivedConfig.isPeacefulBlocked(world)`, never `getDifficulty()` directly.
- Attack lists per type: `ATTACK_RULES` → `RAIDER_/TRAVELLER_/WORKER_ATTACK_LIST` (worker falls back to traveller).
- Workers prefer a target near their owner/leader (`COMBAT.escortOwnerPriorityRadius`).

## Retarget and attacker cap
- **Target switch**: `TargetLockPolicy` (via `TravellerCombatLogic.mayReplaceTarget`) is the only rule allowing a live target to be replaced; never call `vanillaSetAttackTarget` around it. Virtual battles (`VirtualBattleManager.resolveTarget`) use the same lock and `AttackTargetLimiter.capFor`.
- `EntityTraveller.requestImmediateRetarget()` after a kill is a hold-off window; it fires **once per death** (`lastHandledDeadTarget`) or the unit stays "in combat" forever.
- The melee-attacker cap uses `computeEffectiveAttackerCap()` at both call sites (join and per-tick refresh).

## Melee
- Mounted fighters dismount to LED on reaching strike range and curve their approach while closing (see `mount/CLAUDE.md`).
- Never apply durability wear after a call that may have killed the entity (shared `ItemStack` with the drop) — guard on health first.

## Shield
- `ShieldGuard` is the only place that raises/lowers the offhand shield; the block itself is vanilla's, and `EntityTraveller.damageShield` is overridden because vanilla leaves it empty for mobs.
- A unit never holds a bow and a shield: `TravellerEquipmentManager.wantsShield` / `enforceBowShieldExclusion` are the only authorities (the bow wins).
- Strikes go through `ShieldGuard.mayStrike()` (no strike with the shield up or just lowered); an attacker still strikes into a raised shield.
- An absorbed hit (`ShieldGuard.blockedThisTick()`) halves the knockback and mutes the hurt sound; with the shield up the body holds the threat and the gaze cannot drag it (`TravellerBodyHelper`).

## Ranged bow (`EntityAIRangedBowTravellerSafe`)
- Shared by all three types. The bow stays drawn the whole time the task runs, LOS or not (deliberate). Drawn state only through `EntityTraveller.setAimingBow()`.
- Cooldown ticks regardless of LOS; only firing is LOS-gated. Distance-keeping aims at a preferred band. Repositioning checks are throttled. HOLD attitude disables blind-pursuit repositioning.
- **No shot for 3 s** (no LOS, or a shot line that cannot be cleared): the archer ignores the target and lets the normal scan pick a visible one.

## Hostility rules (`combat/`)
- Hired-worker hostility toward players: see `PlayerEnemyRelations` (account-wide) vs `PlayerHostility.localHostilePlayers` (per-worker transient).
- Proactive aggression is suppressed while `campingPassive` (camping, on duty); retaliation is never suppressed.
- **A worker is enemy to anything actively attacking its side** (`Hostility.isAggressingOurSide`), never to its owner's side (`isOwnerSide`); the player's first hit on a peaceful target does not engage workers.
- **War between players lives only in `PlayerEnemyRelations`** (no per-worker list); `offlineProtectionDays` suspends it for an absent player.
