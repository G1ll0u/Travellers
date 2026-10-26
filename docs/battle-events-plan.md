Status: steps 0-5 done; "removed alive" pawns were only the debug reset command (probes removed); wrap-up next

# Battle events: correct battles, one shared visual report

## Goal

A battle is one fight at one place, with right numbers, that ends when the fighting ends; a reconnect or restart
never produces fake battles; the player reads a battle at a glance (map marker and Formation Hub journal).

## Root causes found

1. A loaded participant that is briefly in no world (owner logged out and its chunk unloaded, server restart) is marked
   dead by `stepBattle` (`rec == null && getEntityFromUuid == null`). Its side empties, the battle ends with fake
   casualties, and the result is queued offline: the reconnect spam (journal: ~20 battles ended within 30 s around
   265, 1078, "lost - 1 fell" with 14 foes standing).
2. Two fixed sides keyed by faction (`RAIDER` / `CIVIL` / `OWNER:<uuid>`) predate the current hostility rules.
   `onLoadedEngagement` never checks that the two keys end on opposite sides, so a faction ends on both sides
   (live save: raiders on both sides). Merges are then refused, so one fight splits into several battles, and the
   map label prints "Travellers & Raiders" for both sides.
3. Battle log, one night: lull ends are rare (2 of 87k); one was a 30 vs 24 fight ended 40 s after the owner
   logged out: loaded pawns frozen by vanilla strike no blow and are not simulated.
4. At login ~15 battles were created at once around the player: `onLoadedEngagement` only joins the battle of the
   attacker or target, never a running battle at that place; merges then absorb one per detection pass, and each
   split battle sends its own "under attack" toast.
5. Overnight offline queue: ~60 real battle results, mostly 11 henchmen dying ~12 times each near 650..820, -250..-520
   (respawn at their bed, die again).
6. A battle had the same owner on both sides (side bug, cause 2).

## Decisions taken with the user

- Full redesign scope: correctness, tooltip, offline notices. The map marker itself stays as is.
- Sides are seen from the viewer: "your henchmen", "allies", "enemies", from the real hostility rules. All fighting
  at one place is one battle.
- A participant briefly absent stays in the battle; it is never counted as fallen.
- One visual tooltip ("balance of power") shared by the map marker and the journal, built like the Formation Hub
  group tooltip: a bar per camp (standing in colour, fallen grey), unit-kind icons with counts, fallen names.
- Battles that ended while the owner was offline: one summary toast at login; details stay in the journal.

## Steps

0. **Instrument** — `logs/travellers-battles.log`: create / reopen / merge (or refused, why) / end (reason, counts),
   pawns marked absent, owner notices (online/offline); only battles with henchmen or players. Status: done.
1. **Absent is not dead** — a loaded participant missing from the world stays standing and untargeted, so its side
   is never emptied by it; the battle then ends by lull. Status: done, to verify in the log.
2. **Factions instead of two sides** — participants keep a faction; hostility between factions decides targets,
   joins, merges and the end (no two hostile factions standing). Viewer-relative camps computed for display.
   A new fight inside a running battle's zone joins it; touching battles always merge. Status: done.
3. **End of a battle** — no lull end while a standing fighter is absent or frozen by vanilla (owner logged out);
   an absent pawn leaves the battle after a minute. Status: done.
4. **Offline summary** — battle results queued for an offline owner collapse into one login toast (only when there are several). Status: done.
5. **Balance-of-power tooltip** — shared by map marker and journal; replaces `BattleMarkerLabel` and the journal's
   battle text. Status: done (`battle/client/BattleTooltip`).
