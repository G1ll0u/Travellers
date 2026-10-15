# Worker defense of the owner — plan

Status: implemented and built; awaiting in-game check.

## Goal
Hired workers defend their owner (and each other) against anything that actively attacks them, immersively.

## Decisions (with the user)
- Enemy = anything actively attacking the owner or a worker of that owner (travellers, animals, modded creatures), whatever its type.
- Exclusions: bonded horses, the owner's tamed animals, blacklist, friendly-fire accidents.
- Player's first hit on a peaceful traveller: workers do NOT join; they wait for the retaliation (after the 3-hit grace window).
- Travellers hostile to a player treat that player's hired workers as enemies (reciprocity).

## Steps
1. Add "is actively attacking owner/worker" to `Hostility` (single authority) — done
2. `canRetaliateAgainst` + `TravellerHurtByTarget` + `EntityAIDefendOwnerProactively` use it — done
3. `EntityAIOwnerHurtTargetTraveller`: no longer follows the owner onto peaceful targets — done
4. `PlayerDefenseHandler`: drop the `IMob` requirement — done
5. Reciprocity: `Hostility.isAtWar` (travellers strike back at a worker that fights them); no virtual parity needed, the fight exists only while the owner is present — done
6. Tests for the pure rule; in-game check — done

## Multiplayer audit (decisions)
- Remove `localHostilePlayers` (persisted, only cleared on loaded workers → war resumes after virtualization); the account-wide list is the single source.
- Peace: removing an enemy holds; no automatic re-declaration unless the other side strikes again.
- Offline owners: one config, "days offline before the owner's workers become untouchable by declared wars", 0 = no limit; default 0 = never protected (user choice). Needs a last-seen record per owner (saved on logout).

## Extra steps
7. Delete local hostile list (entity NBT read/write, `MsgSetWorkerHostile` cleanup, `getEffectiveHostilePlayers`) — done
8. Peace holds: `WorkerTeamPvPHandler` only re-declares on a fresh hit, not on a leftover engagement — done
9. Last-seen record + config + check in `PlayerHostility.isEnemyOwner` and `BattleEncounterScanner` — done
