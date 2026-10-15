Status: implemented, build green — waiting for in-game check

# Get Equipment and sale/donation missions: fixes

## Goal
Make the Get Equipment exchange (Barrack, controlled village) and the sale/donation trip robust:
no item duplication, no lost cargo, same result loaded or virtual.

## Decisions taken with the user
- Equipment comes only from weapons, bows and armour; food and other items are never taken.
- Controlled village: every chest of the village.
- An archer keeps its bow; it takes a better bow if there is one, a better (or first) melee weapon, better armour. A worker without a bow never takes one.
- Every follower of the leader is equipped, wherever it is (no distance filter).
- The "stay there" option of Get Equipment is kept.
- Employer offline when the worker reaches the village: the sale happens anyway at the village's base price; reputation goes to the offline player.
- "Employer absent" = not in the worker's world (offline or in another dimension).
- After a sale the worker keeps its mission (cargo, busy, cannot be regrouped) until the cargo is collected. Employer absent at any time of the return: it heads to its Barrack (else waits at the employer's last known spot), with a notice. Employer back in the world: it turns back toward them at once, with a notice when far.
- Village gone on arrival: the worker comes back with its cargo and the player is told.
- Coins that do not fit in the cargo overflow into the backpack.
- Chunk loading: avoid when possible; when needed, rare and cheap (a player-ordered exchange is rare).

## Steps
1. Virtual followers' combat strength refreshed after an exchange. — done
2. Village chests through `MillGoods.withVillageChests`; Barrack chunks borrowed the same way; one path for loaded and virtual; dead "Millénaire barracks tag" branch removed. — done
3. Exchange rewritten on the backpack model with `TravellerEquipmentPolicy` (`isBowUpgrade` added to the manager). — done
4. Sale/donation: Millénaire profile by UUID (offline sale), village-gone return + notice, trade notices queued offline. — done
5. Absent employer: `DeliveryJob.tickEmployerAbsent` (loaded) and `VirtualWorkerMissions.tickReturnToOwner` (offscreen), flag `EmployerAbsent` in the worker NBT. — done
6. Coin overflow into the backpack. — done
7. In-game check. — todo
