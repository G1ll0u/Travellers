# Player follows a traveller / worker — plan

**Status: steps 1-8 done; step 9 (docs + in-game checklist) next.**

## Goal

A player right-clicks a traveller (or presses "Follow" in the dialogue screen / Worker Hub) and is then walked behind the unit hands-off, like an escort follower would: jumps gaps, uses doors, boards boats, keeps up, with a smooth cinematic camera.

## Decisions taken with the user

- **Path**: the player retraces the leader's recorded trail (like `PlayerLeaderTrail` / `tryFollowLeaderTrail`, but reversed: a trail of the unit, followed by the player). No server A*. Traveller hitbox (0.7 x 2.05 by default) is larger than the player's (0.6 x 1.8), so any spot the unit crossed is passable for the player; no player-specific profile.
- **Stop**: any movement key (forward/back/left/right/jump/sneak) cancels. Also automatic stop on damage taken, combat, or when the target is gone for too long.
- **Boat**: when the unit boards a boat, the server teleports the player aboard.
- **Catch-up**: last-resort server teleport after a long blockage (same spirit as the stuck rescue).
- **After any teleport (boat or rescue) the follow stays active.**
- Camera: look-ahead on the route, damped spring, per render frame (not per tick), glides to the unit when it stops.

## Open points (to settle before coding)

- (settled) Rescue: 15 s without gaining 1 block while farther than 12 blocks; to retune after a first in-game try.
- (settled) Camera look-ahead: internal constants, no config.
- (settled) Target virtualized (leaves loaded chunks): stop with a notice.

## Steps

| # | Step | Status |
|---|---|---|
| 1 | Read vanilla: `InputUpdateEvent` (client), server move validation (`NetHandlerPlayServer`), boat entry; confirm no kick on scripted input | done (findings below) |
| 2 | Server: follow session per player (`PlayerFollowSessions`), messages `MsgStartPlayerFollow` / `MsgStopPlayerFollow` / `MsgPlayerFollowState` in `follow/net/`. The trail is recorded client-side (step 3) from the tracked entity, no server trail | done |
| 3 | Client: `PlayerFollowController` (input injection, jump/gap logic, cancel on key) in `follow/client/`; trail logic is the pure `PlayerFollowTrail` (unit-tested). Not reachable in game yet: no entry point until step 7 | done |
| 4 | Client: cinematic camera (`FollowCameraController` + pure `FollowCameraSpring`): looks 4 blocks ahead on the route when walking, at the unit's head when stopped; a manual mouse turn pauses it 1.2 s then it glides back | done |
| 5 | Server (`PlayerFollowTransport`): player boards the unit's `EntityTravellerBoat` when a seat is free, hops beside the unit when it leaves the boat, and hops beside it after 15 s without gaining 1 block while more than 12 blocks away; landing via `LandingSpots`; the client drops its trail on a hop; session stays active | done |
| 6 | Safety stops in `PlayerFollowSessions`: player damaged, target in combat, food <= 6, target lost (dead/virtualized/gone), each with a `NotificationService` notice | done |
| 7 | Entry points: right-click on a plain traveller opens `GuiTravellerInteractChoice` (Talk / Follow him; "Talk" = `MsgTalkToTraveller` -> `EntityTraveller.talkTo`); "Follow him" button in the dialogue screen and in the Worker Hub (right column). Not shown when the traveller is in combat (right-click talks as before). Workers keep opening the Hub on right-click | done |
| 8 | Prototype deleted with the user's agreement: `/followtraveller`, `MsgToggleFollow`, `FollowTravellerClient` (it only moved the camera to the traveller; its walk tick was never wired) | done |
| 9 | `follow/CLAUDE.md` invariants, README line, in-game checklist | todo |

## Risks

- Scripted input on a real player: lava, falls, drowning, mobs. Mitigated by step 6.
- Right-click currently only barks, or opens dialogue when `DIALOGUE.enabled`; the menu must work with dialogue off.
- Loaded-vs-virtual: the unit leaving loaded chunks must end the follow cleanly, never keep chunks loaded.

## Step 1 findings (vanilla 1.12.2, verified in `./sources`)

- `InputUpdateEvent` fires in `EntityPlayerSP.onLivingUpdate` right after `updatePlayerMoveState()`, so `forwardKeyDown`/`backKeyDown`/`leftKeyDown`/`rightKeyDown`/`jump`/`sneak` still hold the **real** keys at that point: read them to detect "player pressed something" before writing `moveForward`/`moveStrafe`/`jump`. Never write the `*KeyDown` fields (it would self-cancel).
- Server (`NetHandlerPlayServer.processPlayer`) trusts client movement and only rejects speed > ~100 blocks²/tick-ish ("moved too quickly") or collision mismatch ("moved wrongly"). Normal walking and sprinting never trip it; our own server teleports are exempt through the usual `setPositionAndUpdate`.
- **Trap**: `jump = true` while airborne and falling with an elytra in the chest slot sends `START_FALL_FLYING`. Inject jumps only when `onGround`.
- Vanilla auto-jump (`autoJumpTime`) already handles 1-block steps; explicit jumps are only needed for gaps and when auto-jump is off in the player's options.
- Sprint is driven by `moveForward >= 0.8` + the sprint key or a double-tap timer; for a hands-off sprint call `setSprinting(true)` ourselves while `moveForward >= 0.8`, never press the sprint key binding.
- `EntityTravellerBoat` does not override `canFitPassenger`: vanilla boat = 2 seats, so a traveller driver leaves a seat for the player (`startRiding` server-side). Mod boats driven by a worker/traveller with a second rider still need an in-game check.
