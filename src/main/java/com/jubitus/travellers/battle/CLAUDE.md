# Virtual battles

Offscreen per-pawn combat (`TravellersConfig.BATTLE_EVENTS`, debug `/travellersdebug battle`).

- `VirtualBattleManager` (WorldSavedData) owns `BattleRecord`s, loot and offline notices.
- `BattleEncounterScanner` detects encounters with a spatial hash; hostility mirrors `TravellerCombatLogic.isCrossFaction`. Joining a battle ignores attitude; only the chase is leash-gated (DEFEND/HOLD mirror the loaded logic in `VirtualFormationSimulator`).
- The sim reuses the physical combat math (`TravellerCombatLogic.computeMeleeDamage`, vanilla `CombatRules`) against a cached `VirtualCombatProfile`. Only virtual-vs-virtual damage is resolved here; loaded participants fight with their normal AI, tracked by UUID. No blow ever crosses the loaded/virtual line (it would be invisible): a virtual pawn whose target is loaded walks up to it until it materializes.
- Pawn movement runs for every battle on each sim pass (`moveBattle`, from the intent the round-robin `stepBattle` sets); `stepBattle` never moves a pawn itself, or pawns jump whenever battles outnumber the per-pass budget.
- Health lives on the record and round-trips through materialize/virtualize; battling pawns materialize at their exact sim position.
- **Loot parity**: how much drops mirrors the physical death drop exactly (workers always drop everything, deliberately). Ghost display items are filtered with the same tag constants on both sides. Looting enchant is deliberately not replicated.
- Loaded fights notify through `onLoadedEngagement` so they get the same journal/map coverage.
- Owner notifications are significance-filtered, one per owner per battle, and only while one of the owner's henchmen still stands in it (a reopened battle keeps its fallen).
- A battle enrols whole marching groups (`BattleGroupLink`): one member fighting pulls in its group, loaded or virtual.
- A battle exists before its first blow but is shown (map, journal, toasts) only from it; it ends when a side is empty or after a lull, and a fight at the same place shortly after reopens it.
- Its zone is measured around the anchor (where blows land) and capped at `engagementRadius`; merge and reopen use it, and a merge never puts one faction on both sides.
- Offscreen healing mirrors the auto-eat AI; raiders excluded.
- A hired worker killed here goes through `ZoneAssignments.onWorkerDied` then `WorkerLifecycleNotifications`, like a loaded death.
