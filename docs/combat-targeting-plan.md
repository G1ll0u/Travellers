# Combat targeting redesign — plan

**Status:** all steps done; awaiting in-game checks, then archive to docs/archive/.

## Goal

Large fights (target: 100 vs 100) where units stay on their target instead of turning on every hit, and archers prefer targets they can actually hit, with minimal server cost. Loaded and virtual combat must pick targets by the same rule.

## Problems found

- Three paths overwrite a live target unconditionally on a hit: `TravellerCombatLogic.onAttacked`, `TravellerHurtByTarget.startExecuting` (priority 2), and the worker `EntityAIOwnerHurtByTargetTraveller` / `EntityAIWorkerHurtByTargetTraveller`.
- LOS is only a filter at pick time (`EntityAIDefendIfClose`); nothing re-evaluates "can I hit this target" afterwards.
- Revenge / call-for-help register an unlimited slot in `AttackTargetLimiter`, so the cap protects nothing in a melee.
- `AttackTargetLimiter.getEffectiveMax`: comment says +1 after 5 s / +2 after 15 s, code gives +4 at both thresholds.
- `onAttacked` runs `hasPathToThrottled` once per hit received (A* per hit).
- Virtual battles differ from loaded: sticky target vs overwritable, fixed cap (`BATTLE_EVENTS.maxMeleePerTarget`) vs dynamic cap, leader-shield bias only virtual.
- `TravellerCombatLogic.setAttackTarget` has the range check and the offscreen reject written twice.

## Decisions taken with the user

1. **Target lock: 4 s.** A live target is replaced only when it became useless (dead, ignored, out of range, archer unable to shoot, see 2) or by the single exception: an enemy within 3 blocks that hits me while my target is farther than 6 blocks.
2. **Archer unable to hit: 3 s** without a possible shot, then it switches to a visible candidate (taken from the candidate list already computed, no extra scan).
3. **Attacker cap kept**, no longer bypassed by revenge / call-for-help; fix the duration bonus to match its intent.

4. **Diversified, situation-aware priority** (units must also defend their group/leader and survive any number of monsters, alone or in a group). One priority ladder for every type:
   1. an enemy attacking my leader / owner (workers: owner; plain travellers/raiders: `groupLeaderUUID`);
   2. an enemy within 3 blocks that hits me (breaks the lock; replaces the narrower "farther than 6 blocks" exception of decision 1);
   3. my current target while valid (the lock);
   4. the nearest target I can reach (archers: one I can hit).

   Lock length depends on context, from allies/enemies already seen by the existing scan (no extra query): **4 s** in a large allied crowd, **2 s** when outnumbered or alone. The attacker cap limits attackers per target, not enemies per unit, so it does not hurt survival.

Also decided by consequence: a hit received only remembers the aggressor (becomes the target when there is none, or when the lock expires); virtual battles use the same score and lock function as loaded combat.

## Steps

| # | Step | Status |
|---|---|---|
| 1 | One shared target-policy function (`TargetLockPolicy`, wired in `TravellerCombatLogic.setAttackTarget` and `trySetAttackTargetLimited`) | done |
| 2 | Hit paths (`onAttacked`, the three hurt-by tasks) route through it instead of overwriting | done |
| 3 | Remove per-hit path computation: use cheap distance/LOS check, A* only when a target is actually adopted | done |
| 4 | Archer "no shot for 3 s" re-evaluation inside the bow AI | done |
| 5 | Limiter: age bonus fixed to +1 (5 s) / +2 (15 s); self-defense bypass kept on purpose (it only covers being attacked, and those units still count in the cap) | done |
| 6 | Virtual `resolveTarget`: same 4 s lock (re-pick only to a strictly better score), loaded cap formula via `AttackTargetLimiter.capFor`; `BATTLE_EVENTS.maxMeleePerTarget` removed | done |
| 7 | Duplicated range / offscreen checks removed from `setAttackTarget` | done |
| 8 | JUnit for the policy and the cap formula (`TargetLockPolicyTest`, `AttackTargetLimiterCapTest`); build green | done |

## Future (not in this plan): fleeing

The user intends a later "flee" behaviour (health, group size, other factors). Keep the target policy free to say "no target, disengage" so a flee decision can plug in later. Nothing to build now; feasibility assessment given in chat.

## Open points to settle during implementation

- Exact player-facing config fields (probably none: lock and archer delay stay named constants).
- Whether the virtual leader-shield bias stays (virtual only today).

## Needs an in-game check

- 20 vs 20 melee: units keep their target when hit by a third party.
- Archers behind a wall / friendly line switch to a visible target after about 3 s.
- Fight starting loaded then going virtual (and back): same target behaviour.
