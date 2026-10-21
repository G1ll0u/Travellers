Status: steps 0-1 done, waiting for a play session to read the battle log

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
3. A battle ends after 30 s without a blow; with escorts leashed and archers holding, probably reached mid-fight
   (to measure in step 0).

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
   pawns marked absent, owner notices (online/offline). Status: done.
1. **Absent is not dead** — a loaded participant missing from the world stays standing and untargeted, so its side
   is never emptied by it; the battle then ends by lull. Status: done, to verify in the log.
2. **Factions instead of two sides** — participants keep a faction; hostility between factions decides targets,
   joins, merges and the end (no two hostile factions standing). Viewer-relative camps computed for display.
   Status: todo.
3. **End of a battle** — from the step 0 log, fix the lull end so a fight still closing in is not ended. Status: todo.
4. **Offline summary** — battle results queued for an offline owner collapse into one login toast. Status: todo.
5. **Balance-of-power tooltip** — shared by map marker and journal; replaces `BattleMarkerLabel` and the journal's
   battle text. Status: todo.
