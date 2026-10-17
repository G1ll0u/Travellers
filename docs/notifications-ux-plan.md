Status: implemented, build green; waiting for the in-game check, then invariants go to notify/CLAUDE.md and this plan to docs/archive/.

# Notifications, journal and battle markers — UX pass

## Goal
Notices that read at a glance (colour = severity), no spam in large battles (rate fit for future per-type sounds), a journal that records everything that happened while the player was not looking, and battle markers that tell the truth.

## Decisions taken with the user
- Severity and journal are two separate properties of a `NotificationDef` (today `NotificationType.EVENT` means both "blue" and "journaled").
- Five severities: DANGER (red), ALERT (orange), SUCCESS (green), INFO (white), REFUSAL (muted grey-pink, separate from danger).
- Journal rule: anything that happens without a player click is journaled; immediate answers to a click are toast-only.
- Battle deaths: one live toast per battle, updated in place ("3 fallen: A, B, +1"), no new toast/sound per death; a result toast at the end (victory / defeat / withdrawal). One journal row per battle, updated, casualties in its tooltip. Deaths outside battles unchanged.
- Global toast rate limiter; DANGER jumps the queue; INFO bursts fold into "+N more".
- Battle markers: real alive counts (loaded or virtual), centre over every fighter, server sends numbers and the client writes the text (lang). Red if the viewer's units are in it, orange otherwise. Force-balance bar + tooltip from the viewer's side. Fights without the viewer's units are shown too, under the existing vision rule; loaded fights without a hired worker are tracked as well.
- Journal row about a worker: click centres the Formation Hub map on it and selects it.

## Settled defaults (user said "go")
- Rate limiter: one new toast per 0.4 s.
- Ended battle marker lingers greyed ~60 s with its result.
- "Withdrawal" = battle ended by timeout or by the viewer's side disengaging.
- Config: replace `showInfo/showWarning/showEvent/showError` by one toggle per severity.
- Delete never-sent defs: `HIRE_SUCCESS`, `HOME_LONE_BARRACK_NOT_FOUND`, `HOME_LONE_BARRACK_ASSIGNED`, `DUTY_WORKER_GROUPED` (`RECRUIT_TOO_CLOSE` kept: the recruit screen now uses it).

## Steps
1. Severity + journal flag on `NotificationDef`; reassign every def; toast left colour bar; journal entries keep their severity (old saved entries mapped). — done
2. Add places to journaled notices lacking one (worker uuid, stable, village). Journal click selects the worker in the Formation Hub. — done
3. Keyed toasts (update in place) + rate limiter in `ClientNotificationCache`. — done
4. Battle story: per-battle toast key, live casualty updates, end-of-battle result notice, one updatable journal row (new update message). — done
5. Battle tracking fixes in `VirtualBattleManager`: loaded fights without hired workers, real alive counts, centre over all fighters, no silent removal skewing counts. — done
6. Battle marker data (structured, per side, viewer-relative) + client text, colour, balance bar, ended-state linger. — done
7. Config toggles per severity; delete unused defs; lang keys. — done
