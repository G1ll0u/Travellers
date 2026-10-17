Status: implemented (A–E) — waiting for the in-game check (F)

# Battle coherence + worldmap marker priority

## Goal
Battles shown to players (map marker, journal row, toasts) match what really happens: one battle per place of fighting, real headcounts even for 80 vs 20, no ghost battles from groups merely passing, a marker that stays put. On the map, places win over units for drawing, hovering and right-clicking, and on-duty workers stay visible around their zone.

## Root causes found
- `engagementRadius` (128 on the server) vs `battleJoinRadius` (32): a battle starts midway between groups that saw each other, pulls in almost nobody, the rest keep walking and open a 2nd/3rd battle; merging needs centres under `mergeBattleDistance` (32).
- Loaded fights enrol a unit only when it takes a target (`onLoadedEngagement`), so the attacker cap per target hides most of a big army.
- `BattleRecord.center` is re-averaged every sim step, so the marker slides.
- Journal tooltip (`FormationHubJournal.battleTooltip`) lists every casualty and every foe name, foes frozen at battle start.
- Map: draw order puts population units and battles above villages; tooltip hit-test uses insertion order, not draw order; right-click ranks units before places; virtual on-duty workers sit exactly on the zone centre.

## Decisions taken with the user
- **Who counts**: as soon as one member of a group fights, its whole group is in the battle (loaded or virtual). The player counts on their side once they hit or are hit inside the battle zone.
- **Visible from the first blow**: a battle may exist internally earlier (virtual pawns closing in) but has no marker, journal row or toast until a blow lands. Small skirmishes get a discreet marker.
- **End**: one side empty, or 30 s without any blow. If fighting resumes in the same zone within 60 s, the same battle reopens (same marker, same journal row).
- **One battle per place**: two battles merge when a group fights in both or their zones overlap; otherwise they stay separate.
- **Marker** is anchored where the fight started; it moves only when the fight has clearly drifted away.
- **Map stacking** (bottom → top): units < places (camps, Barracks, Stables, villages) < battles < player. Hover and click follow the same order.
- **Right-click on a place covering a unit**: one menu, place section on top, unit section below.
- **On-duty workers**: drawn as a ring around their zone's icon at a fixed screen distance, at every zoom, loaded or not.
- **Journal tooltip**: numeric summary — title + duration/outcome, place, your side (standing/fallen), enemies by kind (standing/fallen), your losses as 3 names max + "and N more", then grouped respawn outlook.
- **Fallen toasts**: kept as one story toast per battle, updated at most every ~10 s.

## Settled details
- A distant group member counts only within `battleJoinRadius` of a member of its group already in the battle; a straggler arriving later is enrolled then.
- Skirmish = 6 combatants or fewer in total (both sides): discreet marker.
- `mergeBattleDistance` is removed (zones replace it). Advise the user to raise the server's `battleJoinRadius` to at least 64.
- A battle closed after the 30 s lull announces "broken off" at once; if it reopens within 60 s, the same journal row goes back to "under attack".

## Fix after first server run (decided with the user)
- A camp battle's zone grew to 2000 blocks and chained merges into one 2,358-pawn battle with raiders on both sides. Now: zone measured around the anchor and capped at `engagementRadius`; a merge is refused if any faction would fight on both sides; a pawn farther than `engagementRadius` from the anchor with no target (or only a target that far too) leaves the battle.
- Tooltips (journal and map) show your own henchmen apart from allies on your side.

## Steps

### A. Battle tracking (`battle/`) — status: done
1. `BattleRecord`: add `anchor` (marker position), `zoneRadius`, `firstBlowTick` (-1 = not yet visible), `lastBlowTick`; persist; drop the per-step centroid overwrite, re-anchor only past an internal drift constant.
2. Group membership: one group-identity helper shared by records and loaded entities (traveller/raider `groupLeaderUUID`, worker escort leader, owner-followers). Enrolling one member enrols its group. Loaded members are tracked by UUID like today (`loaded=true`); the sim keeps skipping them.
3. Blow recording: virtual `strike` and a loaded damage hook (unit↔unit and player↔unit inside the zone) set `firstBlowTick`/`lastBlowTick`; a player hitting/hit joins as a loaded participant of their owner key. A player who logs out leaves the battle (not counted fallen); a player death counts as fallen.
4. End rule: replace timeout-only ending with "side empty or 30 s since `lastBlowTick`"; keep `maxBattleDurationTicks` as a safety cap. Reopen: a new battle starting inside the zone of one ended < 60 s ago revives it (same id); same place only, no group condition.
5. Merge: replace `mergeCloseBattles` distance test with "shared group or overlapping zones".
6. Group pull-in runs on every scan pass for every battle (loaded and virtual candidates alike), so loaded and virtual battles count identically.
7. A loaded participant is no longer released after 30 s without a target; it leaves only when it has no target and is farther than `engagementRadius` from the battle centre. A battle with no blow yet ends after 60 s.

### B. Map stream + marker (`worldmap/`) — status: done
1. `BattleMapStream` skips battles with `firstBlowTick < 0` (running and just ended); sends `anchor`; duration counted from the first blow.
2. Skirmish (6 fighters or fewer, decided client-side from the counts): `BATTLE_SKIRMISH` marker, smaller.

### C. Notifications + journal (`notify/`, `follow/gui/FormationHubJournal`) — status: done
1. `BattleSummary`: replace `attackerDescriptions` with side tallies (standing/fallen, enemy kinds with counts), duration, outcome; old journal entries still load (old fields read if present).
2. Start notice only after the first blow; fallen toast throttled to one update per ~10 s per owner per battle (flushed at the end); summary refreshed at each beat.
3. Rewrite `battleTooltip` to the agreed summary; new lang keys in `en_us.lang`.

### D. Map stacking, hover, right-click — status: done
1. Reorder `MarkerType` (declaration = draw order): population, followers/palfrenier/leaders, camps, Home, Stable, villages, battle, destination cross, player.
2. `getTooltipAt` hit-tests in reverse draw order (topmost first).
3. Place over a unit: two-section menu (place section for the current selection, then the unit's section); the selection never changes on opening — the unit section has a "Select" line that switches to that unit's own menu (decided with the user).

### E. On-duty ring (`worldmap/`) — status: done
1. `MsgMapWorkersUpdate.Entry`: add the duty zone position.
2. `DutyRing`: on-duty workers within 32 blocks of their zone drawn evenly around its marker at a fixed pixel radius (widens when crowded); `WorkerMapScreenSupport.drawX/drawZ` use it, so clicks match.

### F. Verify — status: unit tests done; in-game check pending
- `./gradlew build` after each step; JUnit for pure parts (group enrolment, end/reopen rule, merge rule, ring layout).
- In-game: big raider vs worker fight at the server's 128 range (one battle, real counts, steady marker); passing groups create nothing; journal tooltip on a large battle; place-over-unit right-click; Barrack/Stable with several on-duty workers, zoomed out.
