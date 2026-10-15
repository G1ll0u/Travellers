# Raids

Player-targeted raider events. One raid belongs to one player; schedule and state are per player.

- **`RaidWorldData`** is the only holder of raid state (overworld storage). Nothing about a raid lives in `VirtualTravellerManager`.
- **`RaidSchedule`** is pure timing logic (no Minecraft types); `RaidScheduler` is the only caller that decides a raid starts.
- A player who was offline when a raid fell due never gets it on login (rescheduled on first sight per session).
- **A raid raider is identified only by `RaidWorldData.isRaider(world, uuid)`.** Any path that makes a raider camp, visit a village, plan a roam trip or count toward a village quota must test it (camp, visit, `startNewTrip`, `ensureRoutePlanned`, quota counts already do).
- A raid raider's route is set only by `RaidScheduler` (re-aimed at its player every few seconds); its arrival radius is the raid's own (`TravelCommon.RAID_ARRIVAL_BLOCKS`), never `villageNear`/`arrivalRadius`.
- A running raid is maintained (re-aim, end) whether or not raids are `enabled` and whether or not its player is online; `enabled` only gates the scheduling of new raids.
- Raid members are released, not killed, when a raid times out: they become ordinary raiders.
