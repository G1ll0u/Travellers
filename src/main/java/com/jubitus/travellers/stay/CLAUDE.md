# Stays (village visits, rest spots)

A stay is a unit stopping at a place of its trip: travellers, raiders (mirrored, streets only by default) and non-hired workers. One task (`EntityAIStay`) and one offscreen counterpart (`virtual/VirtualStays`) for every kind of place.

- **`StayPlace.at(world, stop)` is the one answer to "what place does this trip stop stand for"** (null = no stay). Everything place-specific (centre, reach, anchors, stay length, places to look at, village data) is read from it; a new kind of place is a new branch there, never in the task.
- **A stop is left only once its stay marks it done** (`TravelCommon.visitedStop`); arrival = arrival radius or inside the town-hall footprint.
- The stay is a `StayState` (persisted, on entity and record, moved at hand-off); the task holds only walking/gaze/talk state, so a fight, restart or virtualization resumes the same stay.
- Head only through `TravellerGaze` foci. Talks are `StayConversation` (loaded-only).
- Leaving is a sequence: out of buildings → back to the hitched horse → a leader waits for its members → `visitedStop`.
- Group followers disperse while their leader stays (`isDispersedInStay`); their stay ends when the leader leaves.
- Offscreen: `VirtualStays.tick`, same draw and sequence.
- Tuning lives in the `VILLAGE_VISIT` config category (name kept: renaming it would drop saved overrides).
