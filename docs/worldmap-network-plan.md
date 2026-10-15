# Worldmap network and battle animation plan

Status: A, G, S1-S4 done. B and C dropped (measured gain too small). D done for Population. E only if measurements justify it.

## Goal

1. Make the worldmap streams cheap enough for thousands of units (server CPU and network), with minimum TPS impact.
2. Animate fights on the maps: archers shoot visible pixels at their target, melee fighters lunge toward their opponent. Animations follow real hits, virtual and loaded.

## Decisions taken with the user

- Strikes are **real events** (who hit whom, when), not a client-side cosmetic loop.
- Melee effect: **icon lunge** only (1-2 px toward the opponent, then back). No spark, no battle-marker pulse.
- Arrow colour: the **side's colour** (same as the icon), so who shoots whom reads at a glance.
- One config boolean to turn the map combat animations off (cosmetic, costs a little network).
- Constants (size, duration, caps) stay internal named constants.
- Map streams must cost nothing when no player has a map open, and stay cheap with thousands of units.

## Findings (verified in code)

- `PopulationMapStream`, `ForeignUnitMapStream`, `WorkerMapStream` and `PlayerUnitVisionSource` (called by both the foreign-unit and battle streams) each scan every loaded `EntityTraveller` and every virtual record, every second: about 5 full passes for the same data.
- Every packet is a full snapshot: strings (name, class, status, role) and UUIDs are resent each second for units that did not move.
- `PopulationMapStream.MAX_ENTRIES_PER_PACKET` is 20000, so one packet can be very large; whether Forge 1.12.2 passes it unsplit is **unverified**.
- `MapStreamUtil.nearestWithinRange` filters all entries per subscriber (O(N) each); the spatial hash built by `ForeignUnitMapStream` is not shared.
- Empty and unchanged packets are sent every second.
- `ForeignUnitMapStream` truncates to 512 entries in `IdentityHashMap` order (not nearest-first), so units flicker; the population stream sorts by distance.
- Minecraft packet compression (threshold 256 bytes) probably softens the real wire cost: **not measured**.
- Strike cost estimate: ~12 bytes each, ~100 strikes/s in a 100-pawn battle, ~1.2 KB/s per viewer, capped at ~128 per packet. Negligible next to the existing streams.

## Steps

| # | Step | Status |
|---|---|---|
| A | **Instrument**: per-stream bytes sent and ms spent per second, exposed in `/travellersdebug` (before any fix). | done (`/travellersdebug map streams start|report|stop`) |
| B | **Shared per-second snapshot**: one pass over loaded entities and virtual records, one spatial grid, reused by Population / Foreign / Worker / Battle streams and the vision source. Zero work without a subscriber. | dropped: ~1 ms/s total, not worth the refactor |
| C | **Skip unchanged / empty packets** (identical to the last one sent to that player). | dropped: population packets almost never identical |
| G | **One priority rule**: nearest-first truncation in every stream (fixes the foreign-unit flicker). | done (Foreign stream; population and worker streams already sorted) |
| S1 | **Record strikes** server-side in a preallocated ring buffer, only while a map subscriber exists: virtual hits from `VirtualBattleManager` (`strike`, `applyDamage`, `loadedStrikeBack`), loaded melee from `TravellerCombatLogic.performMeleeAttack`, loaded arrows from `EntityAIRangedBowTravellerSafe`. | done |
| S2 | **`MsgMapBattleStrikes`** in `worldmap/net/`, registered in `WorldMapMessages`; sent once a second with the same view and vision filters as `BattleMapStream`; cap ~128 per packet; reference units by index/short id where possible. | done (every 5 ticks, strikes carry their real age) |
| S3 | **Client `BattleStrikeAnimator`** (pure logic, clock passed in, like `WorkerMarkerSmoother`): arrow pixel with short trail, ~0.3 s flight, capped simultaneous arrows, hidden when zoomed out; lunge offset applied through the marker draw position. Wired into `GuiTravellerPopulationMap` and `WorkerMapScreenSupport`. JUnit test on the animator. | done (client markers drawn 1.1 s late on a Hermite curve, strikes replayed with the same delay) |
| S4 | Config boolean for the map combat animations; `en_us.lang` key; README line (player-visible). | done (`animateFightsOnMap`, README line) |
| E | **Zoom aggregation**: above a view-range threshold the server sends clusters per 64-block cell (count + type mix) instead of individual pawns; the client draws clusters and switches to pawns when zoomed in. | todo |
| F | **Registry of loaded units** (track join/leave) to replace the per-second entity-list filter. First verify no such registry already exists. | todo, needs check |
| D | **Delta protocol**: static fields (name, class, colour, hired) sent once per unit, then compact (index, x, z, flags) only for units that moved; full resync on map open and every N seconds. Only if measurements after E still justify it. | done for the Population stream (per-viewer id table, `PopulationSync`); Foreign/Worker unchanged |

## Order

A -> B + C + G -> S1-S4 (on top of B) -> E -> D (only if needed). A and B+C+G are independent of the animation work and can ship first.

## Measured (server with ~1000 travellers, one viewer)

- Population stream: ~64 KB per packet, ~34 KB/s deflated, ~1 ms/s server time. Foreign 5 KB/s, Worker 7 KB/s deflated, Battle negligible.
- An 80 KB packet passes unsplit; compression is effective (about 2x).
- Virtual units only move server-side every 10-20 ticks, so smoothness comes from client interpolation.

## Open questions

- Does a single very large `MsgMapPopulationUpdate` pass unsplit through Forge 1.12.2? (affects the priority of E and D)
- Does a loaded-unit registry already exist (step F)?
- Real wire size with Minecraft compression: answered by step A.

## Checks

- `./gradlew build` after each step; JUnit on `BattleStrikeAnimator` and any shared-snapshot pure logic.
- In game (user): arrows visible, lunge visible, no FPS drop in a large battle, map still correct with the option off, no flicker of units at the packet cap.
