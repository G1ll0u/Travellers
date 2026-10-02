# Gaze and greetings

One owner for where a traveller looks; no AI task of its own. Rationale: memory `project_gaze_immersion_redesign`.

- **`TravellerLookHelper` is the only per-tick head turner** (installed over vanilla's look helper in the `EntityTraveller` constructor). An AI task's `setLookPosition` this tick wins; else `TravellerGaze` picks the target; else the head relaxes onto the body. It stands down when something already wrote the head this tick (`noteHeadWriter`: bow, boats, elytra only).
- **`TravellerGaze` decides** by priority: glance > task focus (`INTEREST`, `POINT`, `TALK`) > path ahead > idle look-around. Tasks hand it foci; **never add a private look-around in a task**.
- **`TravellerBodyHelper` keeps the body under the head**; with no path the server copies it into `rotationYaw`, the only body heading clients receive (vanilla clamps the head to 75° off it). Server-side `renderYawOffset` writes alone are never seen.
- A halted mounted rider turns its horse instead (focus only, never a glance).
- Perf: awareness scans and glance choices only run while a player is within `GAZE.watchRange`.

## Greetings (`Greetings`, `TravellerGreeter`)
- Per party, never per entity (player + their hired workers; a group via its leader; a lone traveller). One shared ledger, one hello per ordered pair per cooldown — the right-click hello uses the same ledger. A new social reaction belongs here, not in a new AI task.

## Stopping for a player (`ambient/EntityAIStopAndLookAtPlayer`)
- Movement bit only. Pauses only plain travel or idling (`getRunningMovementTask()` null or `EntityAITravel`); refuses boats, flight, terrain LED, group members, catchup, duty, village visits.
