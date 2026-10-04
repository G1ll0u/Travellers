# Grand nettoyage — feature packages, one authority per question, giant classes split

**Status (2026-10-03)**: plan validated. Phases 0–4 done (phase 3 with items skipped on purpose); phase 5 done (in-game check of 5.4 deferred by the user, to bundle with a later check); phases 6–8 done; phase 9 in progress (next: 9.10).

## Goal

Make the mod cheap for Claude to explore and change: one system = one folder, one behaviour = one place, no two
copies of the same logic drifting apart. Centralisation also makes later performance work easier.

## Decisions taken with the user

- **Feature packages**, not layers: each system owns its client, server, network and AI code in one folder, so its
  `CLAUDE.md` covers the whole system. Verified safe for saves: virtual records store the unit kind as a string
  (`"WORKER"`, `"RAIDER"`…), entities are registered by fixed ids, mixins only target vanilla/Millénaire classes;
  the root package, `mixins/`, `TravellersCoremodPlugin` and `TravellersMixinConfigPlugin` never move
  (named in `gradle.properties` and the mixin JSONs).
- **Config**: delete the 35 dead fields only. Every live field stays in config, even internal tuning (a future
  "advanced" mode in jubiconfig will hide those). Live fields never change category or name.
- **Merging diverging copies**: the strictest version wins by default, but each merge with a visible effect is
  confirmed with the user at its phase.
- **Bugs found during the inventory**: a separate phase 0, one commit per bug.
- **`VillageRelationsCombatHooks`** (never ran: non-static handler never registered): delete, keep today's
  retaliate-only behaviour.
- **Follow stuck counters**: unify onto the travel stuck ladder inside this plan, as the last phase, with in-game
  tests between steps.
- **Virtual record duplicate keys**: keep one source per value; old keys are still read on load (saves compatible).
- **Path budgets**: one budget for every A* user, same numbers as today, and fix the three cross-wirings.
- The remaining phase 4 of `travel-ai-unified-itinerary-plan.md` (shrink `EntityAITravel`) is absorbed by phase 11.

## Rules for every phase

- Each phase (and each numbered step) ends with `./gradlew build` green and the mod playable; one commit per step.
- Pure moves and splits change no behaviour. Anything that can change what a player sees is listed under
  "Visible" and confirmed with the user before it is coded.
- When a system moves, its `CLAUDE.md` moves with it; root "Code map" / "Single authorities" lines are proposed
  to the user, then written.
- Network message ids are assigned by registration order: client and server always run the same jar, so a new
  order is fine, but the order stays deterministic (one fixed list per feature, features in a fixed order).

## Target package tree (root `com.jubitus.travellers`)

| Package | Takes over |
|---|---|
| `unit/` | `entity/travellers/*` + components extracted in phase 9 |
| `unit/culture/` | `entity/culture/*` (choir defs move to `choir/`) |
| `unit/equipment/` | `entity/equipment/*`, `world/equip/{WorkerEquipExchanger,EquipTargetKind}` |
| `unit/render/` | `client/render/{RenderTraveller,RenderLodHelper,models,textures,backpack}` |
| `virtual/` | `world/virtualization/*` |
| `spawn/` | `world/spawn/*` |
| `ground/` | `world/ground/*`, `world/chunkmanager/*` |
| `pathing/` | `world/pathing/**` (sub-packages kept) |
| `travel/` | `entity/entityai/travel/**` (`travelcommon` → `travel/destination` + `travel/`) |
| `follow/` | `entity/entityai/follow/**`, `server/group/*`, `client/gui/formation/*` → `follow/gui`, formation messages |
| `combat/` | `combat/**`, `entity/entityai/combat/*`, `world/data/PlayerEnemyRelations`, `workerplayer` hostility messages |
| `flight/`, `gaze/` | `entity/entityai/flight/*`; `entity/entityai/gaze/*` + `EntityAIStopAndLookAtPlayer` |
| `ambient/` | `EntityAIAutoEat`, `EntityAIFishWhileTravel`, `VillageConversation` |
| `village/` | `world/mill/*`, `world/visit/*`, `EntityAIVillageVisit`, `world/data/TravellerVillageRelations`, `economy/TravellerMoney` (→ `MillMoney`), relations/villages messages + GUI |
| `camp/` | `world/camp/**`, `world/build/*`, `EntityAICampStay` |
| `battle/` | `world/battle/*` |
| `horse/` | new shared core for `mount/` and `zone/stable/` (phase 8) |
| `mount/` | `mount/*` |
| `zone/` | `world/zone/**` (`duty/`, `stable/` kept), `world/equip/WorkerHorsePickup` → `zone/stable`, zone messages, `client/gui/home/*` → `zone/gui` |
| `worker/` | hire, recruit, go orders, missions (phase 9); `workergo`, `recruit`, `workerplayer` (non-hostility) messages; worker screens (`go`, `recruit`, `transport`, `behavior`) → `worker/gui` |
| `dialogue/` | `server/dialogue/**`, `client/dialogue/*` → `dialogue/client`, `client/gui/dialogue/*` → `dialogue/gui`, dialogue messages |
| `worldmap/` | `server/map/**`, `client/map/*` → `worldmap/client`, `client/gui/worldmap/**` → `worldmap/gui`, map messages (incl. the 9 `Msg*` in `server/map/stream`) |
| `notify/` | `notify/*`, `client/notify/*` → `notify/client`, notify messages |
| `marker/` | `server/marker/*`, `client/marker/*`, `client/render/marker/*`, `client/gui/mark/*`, marker messages |
| `sound/`, `choir/` | `client/sound/*`, sound messages; `entity/entityai/choir/*`, `entity/culture/choir/*`, choir messages |
| `gui/` | shell only: theme, buttons, context menu, hub, `ClientGuiHandler`, `CommonGuiHandler`, `TravellersGuiIds`, JEI |
| `debug/` | `debug/**`, `client/debug/*`, debug messages, `CommandTravellerDebug` (split in phase 11) |
| `command/`, `config/`, `mixins/` | unchanged |
| `net/` | the channel and the ordered registry only (`TravellerFollowNet` → `net/TravellersNetwork`, plus `Travellers.NETWORK` chunk packets) |

Inside a feature, client-only code lives in a `client` (or `gui`) sub-package; message classes live in `<feature>/net`.
Classes in `client/` that common code uses (`ModSounds`, `VoiceIndex`, `RaiderVoiceIndex`, `VoicePersonality`,
`TravellerCrowdSoundLimiter`, `BackpackRegistry`, `IBackpackStyle`, `WorkerMarkStyle`, `TravellersGuiIds`,
`ContainerWorker`) land in the common part of their feature.

## Phases (ordered by gain / risk)

### Phase 0 — Bug fixes — status: done (one commit: the fixes share files)

| # | Bug (verified) | Fix | Visible |
|---|---|---|---|
| 0.1 | A raider coming back from virtual gets `travellerMaxHealth` (`EntityTraveller.onConfigReload` :3810, called by `VirtualTravellerManager` :2498; raider has no override) | done: one `applyConfiguredAttributes()` fed by `configuredMaxHealth()`/`configuredFollowRange()`; the raider constructor override and the reload loop's own copy removed | raiders now use `raiderMaxHealth` (default 20, was effectively 25) |
| 0.2 | A worker can be virtualized mid elytra flight (`EntityTravellerWorker.canVirtualizeIgnoringCombat` :3033 lost the parent's `elytraFlight.isActive()` check, `EntityTraveller` :4265) | done | none (no more mid-air vanish) |
| 0.3 | Worker follow range drift: `* 2.0` in `applyEntityAttributes` (:907) vs `* 4.0` in `onConfigReload` (:1712) | done: ×2 (user), one `refreshFollowRanges()` | most workers drop from 256 to 128 blocks of A* range |
| 0.4 | `TravellersDerivedConfig.TRAVELLER_BLACKLIST` is never filled; `EntityAIRangedBowTravellerSafe` :129 and `TravellerIgnoreHooks` :71 use it | done | archers no longer shoot through a blacklisted mob |
| 0.5 | `PlayerDefenseHandler` calls raw `setAttackTarget` on every traveller within 20 blocks: bypasses the attacker limiter, the blacklist, camping-passive, and peaceful | done (user: filtered): raiders, camping/sleeping and blacklisted excluded, limiter, peaceful both ways | fewer travellers pile in |
| 0.6 | `MobTargetInjector` registered three ways (`@EventBusSubscriber`, `Travellers.java` :144 and :245) | done: static `@EventBusSubscriber` only | none |
| 0.7 | `MsgToggleFollow.Handler` (uses `Minecraft`) instantiated on the server (`TravellerFollowNet` :75) | done | none |
| 0.8 | `EntityTravellerRaider.shouldAttackExtraEntity(EntityLivingBase)` :189 is an overload, never reached by callers typed `EntityTraveller` | done | none |
| 0.9 | `TravellerMountSupport.applyMountedGroundSpeed` (:159, :177, :1997) writes a flat speed on the horse, against `mount/CLAUDE.md` | no change: it is the led/hitched horse's speed (no rider to mirror), documented | none |

### Phase 1 — Dead code — status: done

Zero visible change. Every item re-checked by usage search before deletion.
- `VillageRelationsCombatHooks` (whole class).
- 35 dead config fields (list in the inventory): `GENERAL.mpbDebugLog`; 13 of `GROUP_FOLLOW` (the two live
  fields `catchUpTriggerDistance`/`catchUpSpeedMultiplier` stay where they are); `ATTRIBUTES.workerGlobalAssignedCap`;
  `COMBAT.travellerViewBoost*` (2); `WORKER_FORMATION.lavaRepulsion*` (3); 5 `OFFSCREEN`; `STABLES.passiveRescanIntervalTicks`;
  `SOUND.travellerSpeaks`; `CONTROLLED_VILLAGE.barracksSlotsPerBuilding`/`workerBarracksRadius`;
  `RECRUITMENT.recruitLeaderAssignRadius`; `NOTIFICATIONS.historyPanelPosition`; `ROUTING` fine-tier (4).
- Dead fields/methods: `EntityTraveller` (7 unused + `followTimeoutTicks` write-only), `EntityTravellerWorker`
  (6 unused, `catchupRepathCooldown`, shadow `escortLeaderUUID`), `EntityAIFollowLeader` (`findLocalGround`,
  `isNaturalCaveProgressStillHappening`, `sameOwner`, `frontness`, `lastProgress*`, 2 constants),
  `FollowRecoveryHelper.findCaveEntryTeleport`, `EntityAITravel` (`getTargetForDiagnostics`, `lastPos`, `stuckCheckTicks`),
  `TravelTerrain.snowLayers`, `TravellerMountSupport.maybeBondMount`, combat (`canEngageTarget`, `recordEntityHit`,
  `getHostilesForGui`, `isBlacklistMob`, melee `gapToAABBSq`/`isTouchingForMelee`, bow constants),
  `TraceSample.followLeaderLoaded` + leader position.
- Also removed (found while verifying): the never-started "caravan" follow system in `EntityTraveller`
  (`followLeaderId`, `startFollowing`… — `startFollowing` had no caller) and, with the user's agreement, its
  dialogue leftovers (`travelling_with_someone` line, `ActivityKind.FOLLOWING_SOMEONE`). `frontness` and
  `followTimeoutTicks` were wrongly reported dead by the inventory.

### Phase 2 — Feature packages — status: done

Pure moves (git mv + package/import rewrite, tests in `src/test` follow). No logic change. One step per feature,
each building: 2.1 `ground` + `pathing` → 2.2 `unit`, `virtual`, `spawn` → 2.3 `travel`, `follow`, `flight`, `gaze`,
`ambient` → 2.4 `combat` → 2.5 `village`, `camp`, `battle`, `zone`, `mount`, `worker` → 2.6 `dialogue`, `worldmap`,
`notify`, `marker`, `sound`, `choir`, `gui`, `debug` → 2.7 network registry split per feature (`<feature>/net/*Messages.register`,
called in a fixed order from `net/TravellersNetwork`; the mid-method `registered = true` goes to the end).
Each step moves the matching `CLAUDE.md` and updates the root Code map. Visible: none.
Done as planned, with these mechanical deviations from the table: `ground/chunk/` holds the chunk classes;
combat AI tasks sit in `combat/ai/`; `VillageConversation` (package-private, only used by the visit) went to
`village/visit/`; `BackpackRegistry`/`IBackpackStyle` → `unit/backpack/`; worker containers → `worker/inventory/`;
`ClientProxy` → root; `TravellerActivationHandler` → `unit/`, `TravellerOffscreenHandler` → `combat/`,
`ZoneInteractionHandler` → `zone/`; `gui/` keeps the common `CommonGuiHandler`/`TravellersGuiIds` beside the
client shell. Step 2.7 also moved the debug chunk packets from the second `travellers` channel onto the main one
and replaced the 54 per-message `NoopHandler` classes with one shared no-op.

### Phase 3 — Shared small helpers — status: done except the items marked skipped

Done: `TravelCommon.clearRoute/routeTo`, `unit/WorkerLookup` (instead of `WorkerOrderGuard.requireOwnedWorker`;
dismissal clears both hired flag and owner, so the strict check changes nothing in game), `unit/equipment/EquipOrders`
(both Get Equipment handlers), `LoadedChunks.isLoaded/isAreaLoaded` + resweep through `loadTemporarily`,
`combat/lists/EntityRuleList`, `PlayerZoneRecord.contains/box`, `village/MillMoney`.
Skipped on purpose: `WorldTime.isNight` (two lines, not worth a class), `NbtCodecs/BufCodecs` (touching save keys
for little gain), `distSqXZ` (trivial math, no divergence), Barrack/Zone data builders (they really differ:
pending respawns, grouped flag).

### Phase 4 — GUI shared pieces — status: done

4.1 map: `WorkerMapScreenSupport` (feed subscription, places/workers/battles/foreign lists, dimension filter,
standard markers) in the 7 worker-map screens; `WorldMapNavButtons`; `WorldMapController.drawTooltip/zoomStep/panStep/
screenDistSq`; one world→screen transform (`GuiWorldMapPanel`, also used by the zone prompt footprint).
4.2 lists: `gui/ScrollList`, `TravellersGuiTheme.drawScrollbar/scrollbarThumb/drawScaledText/shortenMiddle`,
`gui/GuiClip` (exact scissor formula), `worker/gui/VillageRow`; `GuiWorker` drawn with the theme.
4.3 `GuiWorkerFormationHub` split into `FormationSelection`, `FormationLeaderList`, `FormationSettingsCard`,
`FormationOrdersMenu`, `FormationHubJournal` (1 426 → 705 lines).
Visible (user-approved): one scrollbar look (theme colours, hidden when everything fits) in Barracks, journal and
dialogue; `GuiWorker` map frame like the other screens; `travellers.worker.no_village_in_range` lang key.
Not done: `VillageRowList` (the four list loops now share `ScrollList` + `drawScaledText`; a row widget would add
nothing).

### Phase 5 — Terrain authorities completed — status: done (in-game check of 5.4 pending)

Steps (user): 5.1 teleports → 5.2 column ground → 5.3 block kinds → 5.4 landing spots; in-game check after 5.2 and 5.4.
Decisions (user): every unit teleport clears the path and zeroes momentum; the mod's ground rule everywhere (forest
floor under trees, water surface not seabed).
- 5.1 done: `unit/Relocate.teleport` (horse carry stays in `EntityTraveller.setPositionAndUpdate`); dead `topStandable` removed.
- 5.2 done: go-to targets, formation slot heights, airborne-leader test, fishing, campsite/shelter sampling, virtual
  spawn Y, battle loot, catch-up goals, travel `findSurfaceOrWaterY` (`nearestLayer`, keeps bridges for walkers) →
  `ColumnGround`; cover measures → `ColumnGround.coverAbove`. Kept on purpose: elytra corridor clearance (obstacle top,
  leaves count), chunk-level heightmap scans (map painter, terrain scanner, ChunkReader), dialogue rain check, client
  map click, debug player teleport. Left for 5.4 (they are landing searches): `TravelRescue` surface rescues,
  recruit spawn search, boat dry-shore finder, `TravelTerrain.groundInColumn` (cave-capable).
- 5.3 done: one hazard definition (user: strictest) — `BlockRules.isHazard` now also covers fire, magma and cactus;
  pathing, follow (one `isUnsafeBlock`), gap jumps, hazard repulsion and boats use `BlockRules`; `BlockRules.isIce`
  for camps/shelters. Kept: snow-layer identity checks (one block, no divergence), map colours, dialogue weather.
- 5.4 done (user: one shared test, each caller keeps its preference; strict everywhere; one horse box):
  `unit/LandingSpots` (`whyUnsafe`/`isSafe`: real floor or allowed water, no Millénaire building unless allowed,
  whole body box — horse + rider when riding — hits no block and touches no hazard; `nearest` ring search; `feetY`).
  Used by travel resume hops and surface/shore rescues, the cave follow rescue, the resolver's same-level search,
  zone snap-hops, village visit spots, recruit spawn (fixed: could land inside the town hall or on leaves) and the
  boat dry-shore landing (fixed: no building check). `TravellerMountSupport.landingBox` is the one horse(+rider) box
  (resolver's copy removed, identical math). Dead code removed: `findBestSpotNear`, `findClearMountSpot`,
  `fitsStandingAt`. Allowed inside buildings on purpose: the resolver (it enforces footprints itself at the end),
  a follow rescue joining a leader who is inside, the visit's own indoor / by-the-wall spots, travel waypoints.
  Kept: `TravelTerrain.findGroundNear` (a walking waypoint, cave-capable, not a placement), horse-only placement
  (`findClearHorseFootprint`, Stable pen — phase 8), boat spawn on water.


### Phase 6 — One path budget — status: done

`PathBudget.tryAcquire(world, client, key, priority)`: one row per client (travel, follow, combat, combat scan,
route, itinerary, elytra scan, stuck hop, mount) with today's numbers; follow keeps its per-owner bucket as a policy
and its detailed result. Fixes: combat paths off the follow scheduler; horse paths on a mount row (no 9-in-10
stagger denial); bridge jobs counted once (the route-job gate only ever saw jobs the itinerary budget had already
admitted). Travel already checks its cooldown before acquiring: nothing to fix.
Decided (user): the ungated A* callers (camp, village visit, stable, bow, fishing, follow boat, go-order, claim
mount) stay out of the budget; noted in memory as deferred.
Visible: possibly steadier recompute pacing.
Done: `pathing/PathBudget` (rows TRAVEL, FOLLOW, FOLLOW_MOUNTED, MOUNT, COMBAT, COMBAT_SCAN, ITINERARY, ELYTRA_SCAN,
STUCK_HOP) replaces the six schedulers; follow keeps its owner tokens in `PathScheduler` on top of its rows. Travel's
hard cap (512) was unreachable (128 + 256) and is gone. Led horse: MOUNT row, same number as the mounted follow
ceiling, counted apart (user), no stagger. Combat paths now share the 24/tick combat row with melee.

### Phase 7 — One hostility answer — status: done

`Hostility.isEnemy(self, other)` (faction rule, blacklist, extra entities, worker enemies) replaces the faction XOR
(≈10 copies), the blacklist ternary (×4) and the 4 differing worker enemy predicates; one call-for-help helper
(×3). The `combat/` split between handlers and AI disappears in the single `combat/` package (phase 2).
Visible: the worker predicates disagree today — **the merged rule is confirmed with the user**.
Done: `combat/Hostility` — `isCrossFaction` (every faction comparison) and `isWorkerEnemy` (worker scan, escort
protection, owner-assist base); `EntityTraveller.getAggroBlacklist` is the one blacklist pick. Decisions (user): escorts
now attack raiders near the owner; owner-assist keeps "whatever the owner hits" as a named exception; LavaCow stays on
the config lists (its `IAggressive` detection stays only in the defend-if-close scan, not extended). Retaliation
(`canRetaliateAgainst`) keeps its own rule. Not done: one call-for-help helper — the three sites (traveller
call-for-help, allies-fighting-a-player scan, worker rally) do different things; merging would change behaviour.

### Phase 8 — Horse core — status: done

`horse/HorseMarkers` (pool tag + bond tag), `horse/HorseSnapshots` (capture / spawn from NBT, ≈10 sites),
`horse/HorseAIProfiles` (bond invariants, stable containment, vanilla restore); one death / join / chunk-unload
entry per horse dispatching to mount or stable. Removes the mount ↔ stable dependency cycle. Stable is unpublished,
so its save format may change. Visible: none intended.
Done: `horse/HorseMarkers` (bond owner, generation, orphan timer, pool id — same ForgeData keys, saves unchanged),
`horse/HorseSnapshots` (riderless capture, one corpse test, identity strip), `horse/HorseAI.restoreVanilla`,
`horse/HorseEvents` (one join and one death entry, pool first), `HorseInteractLock` and `HorseTheftGuard` (was
`MountTheftGuard`) moved to `horse/`. `mount/` no longer depends on `zone/stable/`. Visible (user): a released mount
gets its vanilla AI back at once instead of standing frozen until reload; a pool horse whose death another mod
cancels is removed from the pool anyway, and a released pen horse is never respawned from a corpse snapshot.
Kept: bond invariants in `mount/`, containment AI in `zone/stable/` (each used by one system only); chunk unload
stays two handlers (bonded horses depend on which riders unload in the same chunk, `ChunkUnloadVirtualizeHandler`).

### Phase 9 — Unit classes split — status: in progress (9.1–9.9 done)

- `EntityTraveller` (5 177 lines): mount runtime state → `TravellerMountState`; voice → `TravellerVoice`; NBT →
  codec; sleep; hazard/cliff repulsion; virtualization eligibility → `VirtualizationGate` (one rule, subclass hooks);
  combat upkeep → `TravellerCombatLogic`; passthrough getters to `TravelCommon` removed; the 14
  `instanceof EntityTravellerWorker` checks become overridable hooks.
- Shared `FollowCatchup` for traveller and worker (removes the 9 duplicated catch-up pieces and 5 shadowed fields).
- `EntityTravellerWorker` (4 218 lines): `WorkerMissions` (go-home, duty, go order, get-equipment, delivery return),
  `RecruitMission`, `DeliveryJob`; escort and follower counting → `WorkerGroupService` (cycle check and counter
  already exist there); `WorkerNbtComponent` top-level.
Target: no unit class above ~1 500 lines. Visible: none.
Done (one commit each, `EntityTraveller` 5 055 → 3 689 lines): 9.1 `TravellerSleep`; 9.2 `HazardRepulsion`;
9.3 `TravellerVoice` (channel, barks, personality, hello; `TravellerVoice.Kind`; raider hooks `resolveVoiceSetFor*`,
`pickVoicePersonalityId` stay on the entity); 9.4 `TravellerMountState` (`unit.mountState`; the bond UUID stays on the
entity); 9.5 `TravellerHandOff` (border probe, stuck-at-border and forced hand-off; `canVirtualize*`/`getEdgeProbeGoal`
stay as entity hooks; dead `tryVirtualize` removed); 9.6 travel and mount save keys written by `TravelCommon.writeNbt/readNbt`
and `TravellerMountState` (keys unchanged); 9.7 the 21 one-line `TravelCommon` forwards removed (callers use
`getTravelCommon()`; `maybePlayTravelAmbient`/`scheduleNextTravelAmbient` kept: raider overrides);
9.8a `unit/RecruitMission` (`worker.recruit`: tour, return leg, fee/route statics, its save keys).
9.8b `unit/DeliveryJob` (`worker.delivery`: sale/donation trip, return and pickup wait, farewell, trade, its save keys;
the cargo inventory and `returnUsingFollow` stay on the worker).
9.8c worker hostility (`isEnemy*`, `canRetaliateAgainst`, `engageEnemyOwner`, rally helpers, `ownerIdOf`) → the existing
`combat/PlayerHostility` (`worker.playerHostility`); four dead forwards and `getHostility()` removed.
9.8d escort-leader reads back on the worker (they sat in `PlayerHostility`); the loaded follower count
(`countLoadedFollowers`) and the entity's loaded-only cycle guard (`wouldCreateLoadedEscortCycle`) moved to
`WorkerGroupService` unchanged — not merged with `countFollowers`/`chainReaches`, which also see virtual workers
(merging would change behaviour; the count must stay loaded-only, see its doc).
9.9 `setAttackTarget`/`attackEntityFrom` bodies → `TravellerCombatLogic.setAttackTarget`/`onAttacked` (+ `rejectTarget`);
the entity keeps one-line overrides and the `vanillaSetAttackTarget`/`vanillaAttackEntityFrom` bridges; an unreachable
duplicate "hit by owner" block dropped.
Next: 9.10 the `instanceof EntityTravellerWorker` checks → hooks.
9.11 `FollowCatchup`: the traveller and worker catch-up copies differ — list the differences and get the user's
decision before merging (visible behaviour). In-game check still owed for 9.1–9.7 (load a world, voices, camp sleep,
mounts).

### Phase 10 — Virtualization split and record cleanup — status: todo

`VirtualTravellerManager` (2 808 lines) → tick driver + `VirtualMover`, `VirtualTripSimulator`, `VirtualWorkerMissions`,
`VirtualSnapshot` (capture and restore from one field list, so a captured field is always restored).
One goal resolver shared by the loaded worker (`getVirtualGoal`) and the record (`refreshGoal`); mission kind as an
enum, not a string. Record: drop the ≈20 duplicate keys, still read the old ones. Visible: none; a Barrack save
must load (in-game check).

### Phase 11 — AI and command giants split — status: todo

Mechanical splits, behaviour unchanged:
- `EntityAITravel` → waypoint picker, itinerary, path driver, mount policy, stuck actions (closes the itinerary plan's phase 4).
- `EntityAIFollowLeader` → `FollowState` (one reset instead of 5), goal resolver, path requester, speed policy,
  direct steering, idle-at-slot.
- `FollowFormationCoordinator` → leader registry, heading smoother (shared with `GroupFormationTracker`), crowd grid,
  slot assigner, cave geometry; private `SlotOffset` and `wrapToPi` copies removed.
- `TravellerMountSupport` → bond, ticker, riding physics, led-horse driver, mode machine (one `enterFallbackLed`
  instead of 6 pastes), lifecycle, persistence.
- Travel/follow shared bits: gap-jump guard, rider-meets-water probe, one `PARTIAL_PATH_TOLERANCE`; boats: one stall
  watchdog, one `landAshore`.
- `EntityAIRangedBowTravellerSafe` → ballistics, shot-line check, archer movement; `StableHandTask` → chores,
  mount ritual, ambient/night.
- `CommandTravellerDebug` → one class per subcommand. **To decide at the phase**: fold `/travellerChunks`,
  route dump and shelter commands into `/travellersdebug` (player-visible command names).

### Phase 12 — Follow stuck handling onto the ladder — status: todo

The follow AI's 8 stuck counters become one `StuckRecord` evidence feeding `TravelStuckLadder`, following the
lessons in memory (progress = entered a new cell; partial path = failure; no new reset path). Instrument first
with `/travellersdebug trace`, then steps each confirmed in game. **To decide at the phase**: whether the camp,
village-visit, stable and led-horse progress windows join too. Visible: yes — this is a behaviour redesign.

### Phase 13 — Wrap-up — status: todo

Root `CLAUDE.md` (Code map, Single authorities: `LandingSpots`, `Relocate`, `PathBudget`, `Hostility`,
`WorkerLookup`, horse core), memory update, plan to `docs/archive/`.
