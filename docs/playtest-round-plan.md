Status: session 1 (recruiting + money) in review; session 2 (virtual battles) rounds 1–3 done, absent-pawn fix awaiting an in-game check; session 3 done, awaiting in-game check.

# Playtest round: battles, missions, recruiting, money

## Goal
Fix and finish what the user noticed while testing, one theme per session, without breaking notices or loaded/virtual parity.

## Decisions (with the user)
- Order: recruiting + money, then virtual battles, then mission recall + itinerary display.
- Recruiting is offered only to a leader with at least one follower (greyed in the Worker Hub, refused by the server); the whole group goes on the tour.
- Amounts are drawn as denier icons (count then coin) in screens and notices (toasts, journal); `MillMoney.format` emits a token, `gui/MoneyText` draws it.
- Change that does not fit a full inventory after a payment falls at the player's feet; the payment goes through.
- Dismissing a worker hands its purse back first; refused (no room) only if money is left.
- Sale proceeds that fit neither cargo nor backpack go in the worker's purse, handed over when the owner collects the cargo.
- Every battle steps on every sim pass (budget setting removed): with 39 battles and a budget of 4, each step resolved ~5 s of blows pawn after pawn, so the first listed struck first.
- A DEFEND/HOLD pawn whose foe is outside its leash goes back to its post and stays in the battle (no disengage, no rejoin ban).
- All of an owner's henchmen fell = "lost", even when allies won.
- A hired henchman with no weapon and no armour (e.g. just respawned at its bed) is never drawn into an offscreen battle; loaded, it still defends itself.
- The test server keeps `engagementRadius=128` on purpose.
- Recall works on a mission's outbound leg only: sale/donation before delivery (cargo comes back untraded, travel fee not refunded), Get Equipment (nothing exchanged), recruiting. Not offered once a delivery is on its way back. Menu entry "Call back", for a group or a lone worker.
- The Formation Hub map shows the planned route of any selected group that has one (missions and Go orders).
- Virtual battles: read the trace/battle logs on the test server (`user@192.168.1.88:/home/user/server_grandevadrouille_2026/`) before changing anything; ask the user for any setting or test needed; never autotests.

## Steps
1. **Recruiting + money** — done, awaiting review: leader-only rule; denier icons; lost-change, stuck-purse and dropped-sale fixes. Round 2: Hire button fits its price; recruit screen shows one Mission cost (breakdown in tooltip) and a recruits counter, the + greys out at the shared cap. Open: "only real villages, no lone buildings" already holds in code (only Millénaire's village list, within range); waiting for the user to say where they saw otherwise.
2. **Virtual battles** — round 1 done: per-pass stepping, leash stays in battle, lost outcome, battle probes read and removed (nothing abnormal). Round 2: unarmed henchmen stay out of offscreen battles. Round 4: fighters frozen at the edge of loaded islands (Millénaire village tickets) were our melee task's 24-block loaded-area gate; replaced by loaded-only path searches; `stall` probe still in place, remove once confirmed over a few sessions. Round 3: absent pawns were dying pawns re-enrolled during their death animation (probe: all dead, hp 0, chunk loaded); a dying pawn is no longer enrolled. Remove the probe once confirmed in game. Symptoms: units "forget" to fight, fights that never end, absurd losses (24 vs 2 lost 4). Leads to check against logs, none proven: disengage after 30 s without progress then 1 min rejoin ban; no end while a fighter is absent; catch-up steps of up to 10 s resolved in list order (one side strikes first); melee cap per target; leader shield redirect. Notices and battle events must match what happened.
3. **Mission recall + itinerary** — done, awaiting in-game check: recall on every outbound leg; the selected group's (or lone worker's) remaining itinerary drawn on the Formation Hub map, owner colour, nothing on a return to the owner (no fixed route). "Call back" for every mission (sale/donation before or after delivery, equipment, stable horses), designed with the user; show a selected group's planned route on the Formation Hub map (reuse the itinerary debug drawing).
