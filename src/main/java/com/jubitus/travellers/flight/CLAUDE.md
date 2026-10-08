# Elytra flight

Any `EntityTraveller` wearing a usable elytra can glide (`TravellersConfig.ELYTRA`, debug `/travellersdebug elytra`).

- **Physics is untouched vanilla** (entity flag 7 + elytra in the chest slot). The controller only writes pitch/yaw. Never reimplement flight physics.
- The controller is ticked from `EntityTraveller.travel()`, never `onLivingUpdate()` (the look helper would overwrite rotations).
- **No AI task**: flight lives in `EntityAITravel`, the follow AI (`FollowFlightController`) and `travel()` — so raiders need no mirroring.
- `ElytraCapability.canKeepFlying()` is looser than `canTakeOff()`: combat mid-air ends flight by landing.
- **Every exit path terminates**: ground or liquid contact ends a flight, `land()` clears the flag unconditionally, a hard tick ceiling backstops it. Fall damage is cancelled per tick while flaring.
- `ElytraOpportunityScanner` decides takeoffs (glide line clear), budgeted, never loads a chunk.
- **Loaded-only**: `VirtualizationGate` refuses mid-flight; loading/materializing always clears flag 7 and resets the controller.
