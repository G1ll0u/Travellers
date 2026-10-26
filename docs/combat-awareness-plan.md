Status: phase 1 steps 1-7 done; awaiting the user's in-game check before wrap-up (step 8)

# Combat awareness: reassessment, escorts, calmer help calls

## Goal

Fights at long range (`attackTriggerRange` / `engagementRadius` = 128 on the test server) must still look like real
fighters: a unit walking to a far target deals with an enemy that crosses its path, an archer turns on whoever rushes
it, escorts stay by their leader, and a hit does not ripple through 100 allies. From solo fights to 100+ per side, at
minimal server cost.

## Root causes found

1. An engaged unit never looks around: every target scan (`RaiderAttackTargetAI`, `ThrottledAttackTargetAI`,
   `EntityAIDefendIfClose`, `EntityAIDefendOwnerProactively`) skips while the current target is alive; only a hit can
   change target (`TargetLockPolicy` close-hit rule).
2. `attackTriggerRange` means detection, pursuit and "close" at once: `EntityAIDefendIfClose` derives its close bands
   (65 % / 75 %) from it and adds +80 % for bows (230 blocks at 128). `SharedThreatScan` grows its box by the reach on
   the vertical axis too.
3. Help mechanisms fire on every hit and pull every idle ally onto the same attacker: `TravellerHurtByTarget` call for
   help (ally AABB scan per hit), `PlayerHostility.rallyGroupAgainst` (all idle mates), owner-hurt tasks.
   `EntityAIDefendOwnerProactively` runs its own unshared AABB scan per worker.

## Decisions (with the user)

- Two phases: phase 1 individual awareness (this plan), phase 2 group front line, tested in game in between.
- Approach vs contact: a unit still closing on its target is opportunistic; a unit trading blows (contact) keeps its
  opponent unless its leader/owner is attacked or another enemy hits it at close range.
- Archer: a close threat becomes its target (it keeps the bow); once in melee range the existing hand-off draws the sword.
- Escorts (hired-worker escort slots, leader = player or worker): never fight farther than 10 blocks from the leader;
  beyond that they drop the target and come back. Escort archers may shoot a far target but never move away to do so.
- Help calls: one per attacker per group every ~3 s, idle allies only, no more than the attacker cap needs; the rest
  find targets through reassessment.
- Raiders and plain travellers keep no call for help against units (it never recruited anyone): reassessment covers it.
- Mounted melee chase A* is the main combat cost (first report): it gets its own step after reassessment.

## Proposed constants (internal, tuned after the first test)

| Name | Value | Meaning |
|---|---|---|
| reassess interval | 20 ticks, staggered by entity id | how often an engaged unit looks around |
| contact distance | 4 blocks | at or under: "contact" rules |
| close threat | 6 blocks (8 for archers) | an enemy this close, coming at me, beats a far target |
| switch margin | candidate at most half the current distance | anti flip-flop while approaching |
| escort leash | 10 blocks (drop at 12) | around the leader |
| help call cooldown | 60 ticks per attacker per group | |
| scan height | ±32 blocks | every threat scan box; melee units still only pick targets within 5 blocks up/down |

## Steps

1. **Instrument first** — `done`. Profile report section "Combat targeting": target decisions (adopted, refused by
   the lock, switch verdict), help calls with allies pulled in, threat scan cost per kind. Trace `combat` events on
   each decision. Steps 3, 5 and 6 add their own reasons (close threat, escort leash, help cooldown) to the same tally.
1b. **Drop counter** — `done`. "Live targets dropped" by reason in the report and trace.
2. **Split the ranges and stop silent drops** — `done`. Detection ranges are `EngageRange` (`unitAttackTriggerRange` for units/players/attackers, `mobAttackTriggerRange` for the rest) (target tasks
   no longer use FOLLOW_RANGE, bow +80 % removed, fixed 8-block close pick). Height: a seen enemy up to 32 blocks
   above/below is a target for archers (cliffs, walls), melee keeps 5 blocks, unseen beyond 5 blocks is a cave
   (`SharedThreatScan.heightAllows`); an aiming archer is never "stuck". A refused new
   candidate keeps the current target (it used to drop it, sometimes ignoring it for 60 s). Target tasks hand the
   target off once: vanilla's continue/reset no longer drops it at FOLLOW_RANGE or after 3 s unseen; creative players
   are dropped by `disengageIfTooFar`.
3. **Reassessment** — `done`. Every second, an engaged unit not in contact looks at enemies within 6 blocks (8 for
   archers) through `SharedThreatScan.around` (group cache, no new world query in a group) and `Hostility.isUnitEnemy`;
   `TargetLockPolicy` gains contact (target within 4 blocks and a blow exchanged in the last 3 s: only leader
   attacked / hit close move it, no lock expiry) and CLOSE_THREAT (within reach and coming at me, or at most half the
   current distance). Re-asserting the current target is a no-op (it used to re-run the checks and drop it).
3b. **Foreign target tasks** — `done, to confirm`. Removed every tick without vanilla's resetTask (which cleared our
   target on removal and whenever one of our tasks started); the report lists which classes were removed.
4. **Archers** — `done`. Same rule with the 8-block distance; the bow task follows the new target each tick and the
   melee hand-off is distance-based.
5. **Escort leash** — `done`. The follow AI records each worker's escort role (`setEscortSlot`, kept through a
   fight); `getEscortedLeader()` is non-null only while it follows. A melee escort refuses a target farther than 10
   blocks from the leader and drops one beyond 12; an escort archer keeps far targets but stands its ground like HOLD
   (`ArcherMovement.standsGround`).
6. **Calmer help calls** — `done`. `HelpCallGate`: one call per attacker per group every 3 s, shared by the call for
   help and the worker rally; melee recruits only up to the free attacker slots, nearest first; no call for help
   against units. `EntityAIDefendOwnerProactively` stays on its own scan (measured 0.08 ms/tick, not worth moving).
6b. **Lighter mounted chase** — `done`. Mounted fighters chase by `DirectSteering` like pedestrians (each leg proven
   against the horse's corridor, curved approach kept); A* only after being blocked. Also: `RaiderRetaliationInjector`
   no longer gives our own units a vanilla raider-target task (it bypassed budget, lock and leash and dropped targets).
7. **Virtual battles** — `done, no change`. They keep the shared lock duration then move to a strictly nearer / less
   contested foe; "contact" has no meaning without real positions, and a materialized unit re-picks the nearest foe.
8. **Wrap-up** — `todo`. Update `combat/CLAUDE.md` (switch rules, escort leash), archive this plan.

## Phase 2 (later, own plan): front line

Melee members advance together and charge at short range; archers stay behind their line and shoot whoever engages it.
