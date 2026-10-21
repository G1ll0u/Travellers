**Status: implemented, awaiting in-game check.**

# Group equipment: loadout missions + group summary

## Goal
Let the player shape groups by role (archers, shield wall, light melee…) without opening each henchman's
inventory, and see a group's state at a glance, even for groups of 100+.

## Decisions (with the user)
- **Get Equipment takes a loadout**: four ticks Melee / Bow / Shield / Armour (`EquipLoadout`).
  - Ticked = take the best available. Unticked = deposit that kind (worn armour included).
  - Spares are always deposited. Bow and Shield exclude each other when ticking.
  - A worker without a bow takes one when Bow is ticked.
  - Same rule at a Barrack and in a controlled village.
- **The ticks become each member's role**: afterwards it only picks up ticked kinds (ground, mob loot,
  virtual battle loot). A worker that never had a loadout picks up everything, as before.
- **Remembered per group**: the leader's role pre-ticks the next mission; never set = Melee + Shield + Armour.
- **Dump items stays a separate mission**, unchanged.
- **Ticks live in the right-click menu** (`GuiContextMenu.toggle`, menu stays open); the Worker Hub's
  Get Equipment dialog uses toggle buttons.
- **Group summary = tooltip on hovering a leader row** in the Formation Hub (`GroupSummaryTooltip`):
  members, average health + wounded (below half), melee/bow/shield/horse counts (vanilla item icons),
  armour bar by chestplate material with a legend.

## Steps
1. Loadout model + role persistence — done
2. Exchanger obeys the loadout; pickups obey the role — done
3. Messages carry the loadout — done
4. Menu ticks (Formation Hub) + toggle buttons (Worker Hub) — done
5. Group aggregates in `FormationLeaderInfo` + tooltip — done
6. Lang keys — done; user-drawn icons — later, if wanted
