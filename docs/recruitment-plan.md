Status: steps 1-8 done; step 9 (edge cases) next, step 10 to discuss first. Open: repeated village stays (see step 2).

# Recruitment missions by culture

## Goal
A hired worker sent recruiting brings back real units of the world (travellers, unhired workers) of the cultures the player picked, instead of spawning new ones. Takes time, costs fair money, carries risk; identical loaded and offscreen.

## Decisions (with the user)
- **Screen**: cultures present in range, each with reputation and a wanted-count +/-; locked cultures greyed with the reason; shows how common each culture is around (traffic is very uneven between cultures).
- **Eligibility**: Millénaire culture reputation (`UserProfile.getCultureReputation`) above a threshold AND no village of that culture in the player's enemy list (`TravellerVillageRelations`); the enemy rule is a config toggle.
- **Payment**: travel fee + a purse paid upfront. Each recruit is paid from the purse at its own `UnitWorth.hirePrice` when it accepts; remainder refunded on return; purse dropped as coins if the recruiter dies.
- **Route**: per picked culture, its nearest villages in range, one per 3 wanted recruits (config); all stops chained nearest-next from the player, then back to the owner. At most 20 recruits per mission (config).
- **Prices**: travel fee 256 + 128 per village + 0.07 per block (config). Purse = wanted × average hire price of free units of that culture around, + 25 % (config); the server computes the plan and both prices, the screen only shows them.
- **Screen layout**: map on the left showing the planned villages, culture list on the right (name, reputation, chance, -/+ counter); details on hover. The old screen stays in the Orders menu until the new mission works end to end.
- **Encounters, one mechanism**: about every second the recruiter checks the candidates around it (loaded and offscreen alike, same distance test): a small radius on the road (outbound and return legs), the whole village while it waits there. One roll per unit per mission. No hook in the village-visit code.
- **Stay**: the recruiter waits at each village; leaves when the quota is met or time runs out; if still short at the end, adds villages of the missing culture (capped), then returns, still recruiting on the way back while quota and purse allow.
- **Any wanted culture anywhere**: at any stop of the tour, a passer-by of any wanted culture whose quota is not met gets its roll; planned villages only decide where to go.
- **Dead recruit**: a recruit killed before the tour ends frees its place again (the recruiter keeps looking while the purse allows); its price stays spent. Detected by existence (no record, no live body), so any cause of death counts, loaded or offscreen.
- **Notices**: "Recruited N henchmen at <village>" in a village, "... on the road near <nearest village>" on the road.
- **Chance**: base chance 25 % per candidate (a group rolls once), raised by the player's reputation with its culture up to 50 % at Stellar; half that on the road; all config fields.
- **Stay length**: at most 5 minutes per village; config field.
- **Groups**: a candidate in a spawn group joins with its whole group (purse and quota permitting) or not at all.
- **Conversion**: a traveller becomes a worker keeping name, culture, appearance, gear, voice, dialogue memory and its bonded horse. Raiders never.
- **Population**: a hired unit (recruited or not) stops counting toward its village's quota and the worker cap, so the spawner refills it; its home village is kept (dialogue, hire screen).
- **Old mode** (spawning fresh recruits) removed, with its config keys.

## Edge cases to cover
Recruiter dies (purse, recruits already gathered fall back to following the owner); owner offline or other dimension; order cancelled (return, refund); village destroyed or culture turns hostile mid-mission (skip); recruiter or candidate virtual at the moment of the roll; server restart mid-stay; candidate already in combat or on a merchant escort; several recruiters in the same village; purse cannot buy the next group.

## Steps
1. **Population fix**: hired units leave their village quota (normal hiring + merchant group). Separate commit. — done
2. **Measure** village traffic (`profile` section "Village visits"). — done. Open: ~29 % of stays are the same unit restarting in the same village within minutes; user measures on their server ("repeated by" lines), then find the cause. Recruitment rolls once per unit per village regardless.
3. **Eligibility**: `worker/RecruitCultures` (verdict, chance, candidates), `village/MillCultures` (reputation, names). — done
4. **Screen**: `GuiWorkerRecruitCultures` + preview messages, in the Orders menu. — done
5. **Planning + start**: `worker/RecruitPlan`, `MsgRecruitStart` (replans, charges travel + purse), `worker/RecruitTour` (the state, moved between entity and record; arrive / wait / leave / extra villages shared by `RecruitMission.tickTour` and `VirtualWorkerMissions.tickVirtualTour`). — done
6. **Encounters + roll**: `worker/RecruitEncounters`, groups via `worker/SpawnGroup` (was MerchantGroup), outcome counts in the profile report. — done
7. **Conversion**: a record becomes a hired worker (`VirtualTravellerManager.recruitRecord`); a loaded traveller goes through virtualize → convert → `materializeSoon` (horse carried by the hand-off); a loaded free worker is hired in place; recruits fall in behind the recruiter (`WorkerGroupService.joinRecruiter`). — done
8. **Purse**: paid per recruit; the rest refunded when the recruiter rejoins the owner (who may be offline or elsewhere meanwhile: the tour waits, purse kept) or dismisses it; coins dropped where a loaded recruiter dies; lost with a recruiter that dies offscreen. — done
9. **Edge cases** above, lang keys. Open question: there is no order to call a recruiter back mid-tour. — todo (old mode, old screen, cost sync and their config/lang already removed).
10. **Succession** (to discuss, user idea): when the recruiter dies, its successor among the recruits takes over the purse (and the tour?), loaded and offscreen alike. — todo
