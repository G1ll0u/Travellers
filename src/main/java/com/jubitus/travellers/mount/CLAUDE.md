# Horse mounts

Permanent 1:1 bond between an `EntityTraveller` subclass and a vanilla `EntityHorse` (`TravellersConfig.MOUNTS`). Debug: `/travellersdebug mount`, `trace`, `path test --mount`. History and rationale: memory `project_horse_mounts_pathfinding`.

## Authority and state
- `MountMode` (MOUNTED / LED / HITCHED) is an intent. **`TravellerMountSupport.tickMount()` is the only code that mounts, dismounts, leashes, unleashes or moves a bonded horse.**
- `applyBondInvariants()` (saddle in slot 0, AI stripped to swimming) runs on every world-entry path. `mountUUID` is a synced DataParameter.
- **LED is never persisted** — NBT and captures write MOUNTED instead; an LED found at load self-heals.
- **One live copy of a horse**: a live entity, data on its loaded rider (`pendingMountNbt`), or data on its record (`mountNbt`) — never two. Every rebuild from data bumps the bond generation; `MountBondLoadHandler` judges every bonded horse joining the world (stale → removed, released bond → loose, etc.). Link the rider to the horse **before** `spawnEntity`.
- Only `LivingDeathEvent` ends a bond. Horses join and die through `horse/HorseEvents` (death: `HIGHEST` + `receiveCanceled`, pool first); every marker on a horse goes through `horse/HorseMarkers`. Any path that snapshots a horse then removes it checks health first and routes a dead horse to `onBondedMountDied`; `spawnMountFromNbt` refuses corpse snapshots.
- `onBondedMountDied` dismounts the rider immediately (vanilla keeps it riding through the death animation).

## Movement
- Steering stays vanilla (`AbstractHorse.travel()` reads the rider's inputs); the horse never gets its own navigator while ridden. `TravellerMoveHelper` caps turn rate and pivots in place on sharp turns.
- Mounted speed: the horse's MOVEMENT_SPEED is re-mirrored every tick to `rider.getAIMoveSpeed() × UnitSpeed.mountedFactor()` — never copy a flat base speed onto the horse. Offscreen pace uses the same factor.
- The speed ceiling (`UnitSpeed.maxAiSpeed`) holds on horseback in every situation, combat included: a mounted fighter never outruns `attackPercent`.
- **One clearance model**: `PathBody` (which body must fit) + `MountTerrainProfile` (does it fit). Never add a second. The only exception is the static full-AABB sweep in `MaterializePositionResolver` (`findFootprint`), used before the bond exists.
- Mounted travel is direct-ride-first (`EntityAITravel.tryMountedDirectRide`, legs proved by `corridorClear`); A* is the fallback.

## LED (dismount and lead)
- Triggers: terrain (sustained failure + an on-foot route that really **arrives**), low horse health, melee in reach, inside a village radius, giving up on sidesteps. All go through `TravellerMountSupport.enterFallbackLed` (profiler, LED, remount self-heal, optional notice) so one auto-remount path handles them; every remount path refuses while the trigger still holds (horse hurt, inside village).
- Remount only on a sustained strict-dry gate (`canRemountHere`) — never into water.
- A led horse follows the rider's `leadTrail` (`driveLedHorse` steers it every tick); its own A* only on a real jam. Vanilla leash steering is cancelled for bonded mounts (`MixinEntityCreature`). The rider only slows while leading, never stops.
- `leadTrail` is recorded whenever a rider has a bonded horse (also used by mounted followers).

## Village hitch
- Entering the grounds of the village it's visiting, the rider goes HITCHED with `villageHitchStop` (never on a building, in water or mid-jump). A hitched horse with that marker is never recalled; the rider walks back to it. A horse never vanishes in view: an unloading post turns it into data on the rider and it is put back only at the post.

## Players and sync
- `horse/HorseTheftGuard` blocks players from bonded horses at both interact and `EntityMountEvent`, both sides; the client learns the lock from `HorseInteractLock` (synced via `MixinEntityHorse`).
- A rider that looks frozen while its horse walks off is a tracking desync, never an AI bug: `MountSyncReconciler` (world tick) repairs it; `MixinEntityTracker` gives the mount its rider's tracking range.
- A rider defends its horse by feeding the attacker to its own hurt-by-target AI (`MountDefenseHandler`) — no separate targeting rule.

## Rider death
- A dead rider's horse goes loose exactly where it died (`OrphanMountCleanup`), no redirect, unless `mountDiesWithRider`. Cleanup never force-loads a chunk.
- A hired worker can claim a loose horse (`EntityAIClaimLooseMount`, lowest priority, walks over, bonds via `bondToLiveHorse`). Pool horses (Stable) are never claimed or bonded this way.

## Placement
- A led/recalled horse is placed horse-body-only (`findClearHorseFootprint`); a MOUNTED placement resolves the full silhouette. A mounted record materializes horse-sized and remounts on the spot when grounded and dry.
- `setPositionAndUpdate` carries the horse along: every rescue-hop landing for a rider with a horse must be checked against the mounted footprint.
