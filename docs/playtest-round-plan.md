Status: session 1 (recruiting + money) in review; session 2 (virtual battles) round 1 done, absent-pawn probe awaiting server logs; session 3 not started.

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
- Virtual battles: read the trace/battle logs on the test server (`user@192.168.1.88:/home/user/server_grandevadrouille_2026/`) before changing anything; ask the user for any setting or test needed; never autotests.

## Steps
1. **Recruiting + money** — done, awaiting review: leader-only rule; denier icons; lost-change, stuck-purse and dropped-sale fixes. Open: "only real villages, no lone buildings" already holds in code (only Millénaire's village list, within range); waiting for the user to say where they saw otherwise.
2. **Virtual battles** — round 1 done: per-pass stepping, leash stays in battle, lost outcome, probe `left-world` / `death-unindexed` in `travellers-battles.log`. Open: why loaded pawns go absent (read the probe lines next session). Symptoms: units "forget" to fight, fights that never end, absurd losses (24 vs 2 lost 4). Leads to check against logs, none proven: disengage after 30 s without progress then 1 min rejoin ban; no end while a fighter is absent; catch-up steps of up to 10 s resolved in list order (one side strikes first); melee cap per target; leader shield redirect. Notices and battle events must match what happened.
3. **Mission recall + itinerary** — todo. "Call back" for every mission (sale/donation before or after delivery, equipment, stable horses), designed with the user; show a selected group's planned route on the Formation Hub map (reuse the itinerary debug drawing).
