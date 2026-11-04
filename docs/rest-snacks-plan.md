Status: done, waiting for the doc lines before archiving

# Rest snacks

## Goal

During a seated rest-spot break, a unit sometimes drinks and/or eats an item of its own culture (cider for a Norman, wine for a Byzantine…), with the arm brought to the mouth, the sound and the crumbs. Purely cosmetic: no healing, no effect, nothing taken from any stock.

## Decisions taken with the user

- **Menu per culture, automatic + config**: read at server start from the culture's Millénaire goods as loaded (custom packs merged): goods not marked `foreigntrade`, whose item is eaten or drunk; raw ingredients (smelt into a food), potions and non-food dropped. Config can add items per culture and exclude items globally; default exclusion `millenaire:oliveoil`. A culture with nothing gets a small vanilla menu. Each culture's menu is logged once at start.
- **Specialties x3**: an item not from vanilla Minecraft is picked three times as often.
- **Only while seated** (stay activity `SIT`), never standing or walking.
- **About 6 breaks in 10**: drink, eat or both, 1–3 sips/bites each, then enjoy the view. Chance is a config field.
- **Our own arm animation** (1.12 has no third-person eat/drink pose): arm to the mouth with a bob, drinking higher with the head tilted back. Also used by the existing heal-eating.
- **Display-only held item**: the snack never touches the real main hand. The heal-eating (`EntityAIAutoEat`) moves to the same display, which fixes a bow or weak tool in the main hand being deleted by eating, and raiders' meals being hidden by their weapon.

## Design

- One unit component (like `TravellerSit`): synced snack item + start tick, never persisted, ends itself the moment the unit stands, is hit, fights, moves or is virtualized. A unit always comes back from offscreen empty-handed.
- Does not use vanilla item use (`setActiveHand`): no `onItemUseFinish` on modded foods, no interaction with the `isHandActive()` busy checks.
- Sound server-side (eat / drink), crumbs client-side from the synced item.
- Started by `EntityAIStay` while the `SIT` activity holds; raiders get it through the same task.

## Steps

1. Culture menu (`village/CultureSnacks`, config `snackChance`, `snackExtraItems`, `snackExcludedItems`) — done (log checked)
2. Snack component (`unit/TravellerSnack`) + sync + self-cancel rules — done
3. Arm/head animation (`ModelSimpleHumanoid`), held snack (`LayerTravellerHeldItem`), crumbs, sounds — done, angles to tune in game
4. Hook into the `SIT` activity (`TravellerSnack.planWhileSeated`, trace category `snack`) — done
5. Move `EntityAIAutoEat` to the display-only item (`TravellerSnack.holdForMeal`) — done
6. Lang — nothing needed (config comments live in code, no player-facing text)
