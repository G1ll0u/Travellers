# Virtual battles

Offscreen per-pawn combat (`TravellersConfig.BATTLE_EVENTS`, debug `/travellersdebug battle`).

- `VirtualBattleManager` (WorldSavedData) owns `BattleRecord`s, loot and offline notices.
- `BattleEncounterScanner` detects encounters with a spatial hash; hostility mirrors `TravellerCombatLogic.isCrossFaction`. Joining a battle ignores attitude; only the chase is leash-gated (DEFEND/HOLD mirror the loaded logic in `VirtualFormationSimulator`).
- The sim reuses the physical combat math (`TravellerCombatLogic.computeMeleeDamage`, vanilla `CombatRules`) against a cached `VirtualCombatProfile`. Only virtual-vs-virtual damage is resolved here; loaded participants fight with their normal AI, tracked by UUID.
- Health lives on the record and round-trips through materialize/virtualize; battling pawns materialize at their exact sim position.
- **Loot parity**: how much drops mirrors the physical death drop exactly (workers always drop everything, deliberately). Ghost display items are filtered with the same tag constants on both sides. Looting enchant is deliberately not replicated.
- Loaded fights notify through `onLoadedEngagement` so they get the same journal/map coverage.
- Owner notifications are significance-filtered, one per owner per battle.
- Offscreen healing mirrors the auto-eat AI; raiders excluded.
- A hired worker killed here goes through `ZoneAssignments.onWorkerDied` then `WorkerLifecycleNotifications`, like a loaded death.
