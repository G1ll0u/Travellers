Status: step 1 implemented, awaiting commit.

# Recruitment missions by culture

## Goal
A hired worker sent recruiting brings back real units of the world (travellers, unhired workers) of the cultures the player picked, instead of spawning new ones. Takes time, costs fair money, carries risk; identical loaded and offscreen.

## Decisions (with the user)
- **Screen**: cultures present in range, each with reputation and a wanted-count +/-; locked cultures greyed with the reason.
- **Eligibility**: Millénaire culture reputation (`UserProfile.getCultureReputation`) above a threshold AND no village of that culture in the player's enemy list (`TravellerVillageRelations`); the enemy rule is a config toggle.
- **Payment**: travel fee + a purse paid upfront. Each recruit is paid from the purse at its own `UnitWorth.hirePrice` when it accepts; remainder refunded on return; purse dropped as coins if the recruiter dies.
- **Route**: villages of the picked cultures, sized to the wanted counts, ordered nearest-first, then back to the owner.
- **Stay**: the recruiter waits at each village; every traveller / unhired worker of a wanted culture whose village visit happens there gets one roll. Leaves when the quota is met or time runs out; if still short at the end, adds villages of the missing culture (capped), then returns.
- **Chance**: base chance raised by the player's reputation with the candidate's culture.
- **Groups**: a candidate in a spawn group joins with its whole group (purse and quota permitting) or not at all.
- **Conversion**: a traveller becomes a worker keeping name, culture, appearance, gear, voice, dialogue memory and its bonded horse. Raiders never.
- **Population**: a hired unit (recruited or not) stops counting toward its village's quota and the worker cap, so the spawner refills it; its home village is kept (dialogue, hire screen).
- **Old mode** (spawning fresh recruits) removed, with its config keys.

## Edge cases to cover
Recruiter dies (purse, recruits already gathered fall back to following the owner); owner offline or other dimension; order cancelled (return, refund); village destroyed or culture turns hostile mid-mission (skip); recruiter or candidate virtual at the moment of the roll; server restart mid-stay; candidate already in combat or on a merchant escort; several recruiters in the same village; purse cannot buy the next group.

## Steps
1. **Population fix**: hired units leave their village quota (normal hiring + merchant group). Separate commit. — done
2. **Measure** village traffic with `/travellersdebug profile` to set default stay time and chance. — todo
3. **Eligibility** authority + culture list message (server → screen). — todo
4. **Screen**: culture rows, counters, fee and purse preview. — todo
5. **Planning**: village selection per culture, ordering, purse escrow, persistence on entity and record. — todo
6. **Stay + roll**, hooked on village-visit arrival, loaded and virtual. — todo
7. **Conversion** traveller → worker (data, group, horse), loaded and virtual. — todo
8. **Purse**: pay per recruit, refund, drop on death (loaded and virtual death). — todo
9. **Edge cases** above, old mode and config removal, lang keys. — todo
