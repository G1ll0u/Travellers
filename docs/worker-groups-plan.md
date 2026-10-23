**Status: implemented, awaiting in-game check**

# Escorted merchants (worker groups)

## Goal
A roaming (unhired) worker usually travels escorted by other workers, so it no longer dies alone on the road. The player can only hire the whole group, at the price of the whole group.

## Decisions taken with the user
- Group roll like travellers: chance + min/max size in `SpawnWorkers`, defaults 75 %, 2–4 (leader included). A group counts in full toward its village's worker quota.
- The leader is the merchant: backpack, village donations. Followers: no backpack, no donation, same equipment as any worker.
- Leader dies while unhired: the best-equipped follower takes over (`GroupSuccession`) and becomes the merchant (backpack + donations).
- Clicking any member offers to hire the group; the displayed price is per-worker cost × members (loaded and virtual). Only the whole group can be hired.
- Once hired, every member is an ordinary hired worker following the player, ungrouped (no escort formation).

## Design
- An unhired worker group **is a plain traveller group**: `groupLeaderUUID`, `GroupFormationTracker`, `GroupSuccession`, village visits, camps, offscreen ring. Follow code that today means "worker" (`instanceof EntityTravellerWorker`) really means "hired escort" and must test that instead.
- A worker carries at most one leader link: `groupLeaderUUID` (unhired) or `escortLeaderUUID` (hired). Hiring clears `groupLeaderUUID`.

## Steps
1. Config + spawn worker groups as virtual records. — done
2. Offscreen simulation: worker group followers take the group-follower branch of `refreshGoal` / movement. — done
3. Loaded follow AI: unhired worker followers use the group path (follow target, gates, formation, leader-side checks). — done
4. Succession for unhired worker groups (loaded and virtual death hooks). — done
5. Backpack hidden and donation skipped for followers. — done
6. Group hire: server hires every member (loaded + virtual) for cost × N; client shows "Hire group (N)" and total price. — done
7. Build, review, in-game check list. — built + unit tests pass; in-game check pending
