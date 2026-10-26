# Player zones, Stable, duty

A player zone is a **Barrack** (worker housing) or a **Stable** (horse pool). Shared bookkeeping: `PlayerZoneRecord`/`PlayerZoneManager`; per-type differences live in `ZoneTypeDef` + a `ZoneTypeData` class. A new zone type = one `ZoneTypeDef` constant + one `ZoneTypeData` class. Created with the Millénaire Summoning Wand; `MsgCreatePlayerZone` re-validates everything server-side (size, loaded box, required roles). The Stable system is unreleased: its save format may break; **the Barrack's may not**.

## Assignment
- A worker has one `ZoneRef`; home and duty are derived from it, never stored twice.
- **`ZoneAssignments` is the only code that binds (`assign`) or releases a worker**, loaded or virtual, always with a bed. Every path uses it (messages, zone deletion, lost bed, audit, `onWorkerDied`).
- `ZoneRoleRescan` is the one place role blocks are re-synced and the one bed rule; never rescans an unloaded box.
- Barrack save compatibility: the `"TravellersLoneBarracks"` key, the `BARRACK` default and the old tags are load-bearing (`ZoneRef.read`, `resolveLegacyZoneRef`).
- Bed respawn (`tickRespawns`): the worker comes back beside its bed — or as a virtual record there — never "when a player comes by"; it returns un-grouped with no order, mount bond dropped.

## Stable horse pool (`stable/`)
Rationale: memory `project_stable_zone_feature`.
- **A pool horse is a live entity XOR data.** A Stable is live when vanilla ticks its whole box (`StableCoordinator.isZoneTicking`), else dormant. Three doors keep it exact: going dormant captures every live horse; `StableChunkUnloadHandler` captures a horse whose chunk unloads; `StableHorseLoadHandler` accepts a joining pool horse only if its entry links that exact entity (link before `spawnEntity`).
- `HorseMarkers.isPoolHorse` is the one identity test. **A pool horse is never a mount**: every way in strips mount markers.
- `StableWork` holds the rules (pure state changes through a `ChestSource`); live `StableHandTask` and dormant `timerPass` both use it. `tamed`/`saddled`/`temper` are owned by those two only. Progressions are elapsed-time anchored. Whenever `timerPass` doesn't run (live, night, no hand) `holdAnchors` keeps the anchors at now: that time is never replayed as a catch-up.
- Breeding builds a `BRED` entry directly; `EntityAIMate` is stripped (double counting).
- Bonding happens only at Get Equipment pickup (`WorkerHorsePickup`); a horse respawned from a live snapshot gets its UUID stripped.
- Deleting a Stable turns its horses into ordinary owned horses where they are.
- Debug: `/travellersdebug stable list|dump|log|verify`.

## Duty (`duty/`)
- `WorkerMode.ON_DUTY`; assigning a duty zone sends the worker at once, unassigning is the only recall.
- One task, `EntityAIWorkerDuty`, delegates to the `WorkerDutyBehavior` for the duty kind; its `resetTask` calls the behavior's `stop`. `VirtualDutyBehavior` is the offscreen side.
- **Nothing else pulls a worker off duty**: no follow, orders refused (`WorkerOrderGuard`), no stop-for-player, passive posture (retaliation only), no choir, loose-mount claim below the duty task. A new worker AI must respect this.
