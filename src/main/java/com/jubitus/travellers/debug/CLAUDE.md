# Debug tools

All under `/travellersdebug`: one class per subcommand in `debug/cmd/`, registered in `CommandTravellerDebug`'s table.
- `/travellersdebug` may arrive on the RCON thread; `execute` hops to the server thread — never read the world before that hop.

## Per-traveller trace (`trace/`) — the tool of record for one unit's movement
- One file per traced entity in `logs/travellers-trace/`. Lines: `S`ample / `E`vent / `!`incident / `~`replayed context, pipe-separated `key=value`; channels `move`, `path`, `steer`, `mount`, `ai`, `follow`.
- Every loaded traveller keeps a rolling ring buffer; a stall replays it into a fresh file, so the approach to a failure is captured. Each incident is followed by a terrain snapshot.
- `PARTIAL path: …` exposes vanilla's best-effort routes. Live HUD via `TRACE.showHud` (formatted server-side). Free when `TRACE.enabled` is off.
- `trace start|stop|list|channels`.

## Movement profiler (`profile/`)
- Population-wide counterpart: `profile start|report|stop|status` → `logs/travellers-profile/`. Every hook is one null check while no session runs; counters stay in memory until a report.
- Anomaly kinds are the `MovementAnomaly` enum: add a kind there and call `MovementProfiler.anomaly()` next to the event — never parse trace text.

## Path A/B testing (`path/`)
- Spawns a throwaway worker between two chosen points using the real travel code, compared against a hand-marked ideal route (`pos1`/`pos2`/`mark`, `test [--mount] [--waypoints]`, `export`).
- A spawned worker needs `setTargetVillage()` **and** `getTravelCommon().setReadyToDepart(true)`, or the travel task is yanked every tick.

## Autotest
- `autotest forest <x1> <z1> <x2> <z2> [solo] [followers] [seconds]|status|stop`: mounted forest crossing scenario; `servertest.py forest` runs it on 3 fixed routes.

## Others
`destination report|log`, `relations`, `village …`, `stable …`, `map …`, `camp reset`, `restspot here|stats|rescan`, `battle`, `mount`, `elytra`, `terrain region`, `dialogue memory dump`, `reset [workers]`.
- `freeze/FreezeWatchdog`: always-on, writes `logs/travellers-profile/freeze_*.txt` when most units that should move are still, or most loaded units stop ticking.
- `MapJumpLog` writes every too-fast unit move to `logs/travellers-mapjumps.log` while any map is open, with the loaded/virtual hand-off and the materialize placement.
