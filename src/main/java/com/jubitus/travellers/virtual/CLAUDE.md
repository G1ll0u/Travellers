# Virtualization

Every `EntityTraveller` subclass is virtualized instead of despawning: `setDead()` + a `VirtualTravellerRecord` in `VirtualTravellerManager` (WorldSavedData), `entityClass` = WORKER / TRAVELLER / RAIDER. Zero chunk loading offscreen. Audit history: memory `project_virtualization_handoff`.

## When
- **One eligibility rule, `VirtualizationGate.mayVirtualize`**: physical blockers only, identical for every unit kind; combat or a mission never blocks it (the offscreen simulation finishes them).
- **Materialize/virtualize follow whether vanilla would tick the unit, never distance to a player.** `isFrozenByVanillaAt()` mirrors both vanilla gates: the per-entity loaded-area gate and the per-world gate (an empty unticketed world stops ticking entities 15 s after the last player leaves). The two sides stay exact complements. The materialize debounce counts continuous time on ticking ground, never time in one chunk.
- **A unit frozen by vanilla does not tick, so nothing in its own tick can rescue it** — border problems are fixed from the world tick (`checkVirtualize`, `TravellerHandOff.forceResolvingBlockers`).
- **A loaded unit only leaves the world for unloaded or frozen ground**: `TravellerHandOff.tryAtLoadedBorder` refuses unless the ground ahead is unloaded; a refused virtualize never falls back to `setDead()`.

## Loaded XOR virtual — three doors
- `materializeEntity` removes the record right before `spawnEntity` and puts it back if the spawn is refused (vanilla refuses silently).
- `virtualizeWorker`/`virtualizeTraveller` return a real boolean and refuse when a record already holds the identity; `TravellerHandOff` propagates it.
- `TravellerLoadDedupHandler` kills a joining body whose identity a record already holds.

## Hand-off
- Itinerary and village-visit state are moved as objects (`handOverItinerary`, `handOverVisit`), never copied through entity NBT; `materializeEntity` sets them unconditionally.
- AI flags and nullable UUIDs are reset unconditionally at materialize (`if (x != null)` keeps stale NBT).
- Adding state: see the checklist in the root `CLAUDE.md`.

## Offscreen movement
- Route-driven records glide along their itinerary (`advanceVirtual`, allocation-free); `refreshGoal`/`tickRoamRoute`/`tickPersonalTravelRoute` own leg-level decisions. `routeDrivenMovement(r)` must mirror the loaded `isItineraryDriven()`.
- Personal self-travel (Go Home, duty) is handled in `refreshGoal` above the `workerMode != IDLE` guard.
- Group follower records track the leader offset by `MaterializePositionResolver.followerSpreadOffset()` in both leader branches; a leader gone without a death hook triggers `GroupSuccession.onLeaderMissing`.

## Where a record reappears (`MaterializePositionResolver`)
- Far-travelled records are surface-clamped; others keep their frozen Y (indoors, underground). Liquid/hazard floors rejected, tree canopies descended (`BlockRules.isTreePart`/`isCanopy`), Millénaire footprints pushed out (`MillFootprints`), mounted records resolved horse-sized.
- `MATERIALIZATION.logEvents` logs every virtualize/materialize with its reason (`VirtualizationLog`).
