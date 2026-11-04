Status: implemented, waiting for the in-game check

# Tavern drinks

## Goal

During a village visit, a traveller or unhired worker goes into a tavern (or a leisure building), stands at the counter and drinks its culture's drink, sometimes with a bite. Loaded and offscreen behave alike.

## Decisions taken with the user

- **Buildings**: Millénaire tags `inn` and `leasure`.
- **Who**: travellers and unhired workers. Raiders stay in the streets, as for every building.
- **Where**: standing at the building's leisure point (spread a little so drinkers don't stack). No seat search.
- **What**: always drinks (1–3 sips from the culture menu of the rest snacks), sometimes eats too.
- **How often**: one drink guaranteed per stay when the village has such a building; more drinks come from the normal activity draw with their own weight (config, default 3, beside stroll 4 / building 3 / panel 2).
- **How long**: 30–90 s (config min/max). The time at the counter **adds to** the stay instead of eating into it, so the 10–45 s stays without tavern don't change.
- **Offscreen**: the record is "inside" logically, with no real position (never placed inside a building). If it materializes during its drink, it appears at the door and walks back in to finish. Accepted gap: a player already inside sees an empty tavern for a few seconds.

## Design

- `VillageVisitIndex.Target` gets a `drink` flag: the building has a drink tag and a leisure point (indoors or out; outdoors needs no "inside" handling).
- `StayState` (persisted): drinks had, and the drink in progress (building target + time left). A fight, restart or hand-off resumes the same drink.
- Loaded: new `EntityAIStay` activity `DRINK`, legs like a building visit (entrance → counter → entrance). No real A* path to the counter: it drinks standing at the door, facing in (what an offscreen drinker does).
- `TravellerSnack` gets a standing mode for the counter: same self-cancel rules (moves, hit, fight, virtualize), started only once the unit stands still at the counter.
- Offscreen: `VirtualStays` makes the same draw; while drinking the record waits at the entrance and its time counts down.
- Leaving (stay over, leader leaves) interrupts a drink like any building visit: out through the entrance.

## Proposed config (category `VILLAGE_VISIT`)

- `drinkBuildingTags` = `inn`, `leasure`
- `weightDrink` = 3
- `drinkMinSeconds` = 30, `drinkMaxSeconds` = 90

## Steps

1. Index flag + config fields — done
2. `StayState` fields + NBT — done
3. Standing snack at the counter (`TravellerSnack`) — done
4. Loaded `DRINK` activity: guaranteed first drink, weight after, stay extension — done
5. Offscreen drink in `VirtualStays` + resume at materialize — done
6. Trace (`visit` events `drink …`, `snack` events), `/travellersdebug village visit <name>` shows drinks, build — done
