# Notifications

Toasts + a persistent journal replace player-facing chat prints (debug output and in-character dialogue stay in chat).

- **`NotificationService` is the single dispatch helper** (`notify`, `notifyEvent`, `*Offline` variants). Call sites use typed `NotificationDefs` constants, never raw strings.
- A notice may carry a `NoticePlace` (clickable journal row, "39m to the SE" in the toast) and a `NoticeLine` detail.
- The server sends key + args; the client resolves them. **Distance/direction phrases are computed client-side at display time** (`LocationFormat`), so an offline notice is relative to where the player logged back in.
- Stores: `NotificationQueueData` (offline delivery) and `NotificationJournalData` (EVENT-only history, structured entries, never pre-formatted strings).
- Hired-worker death/respawn notices all go through `WorkerLifecycleNotifications`, called **after** `ZoneAssignments.onWorkerDied`.
- Mission results go through `MissionNotifications` (from loaded and virtual paths alike); short Go moves stay silent.
- Rendering: `NotificationOverlayRenderer` draws on `DrawScreenEvent.Post` for `ITravellersGui` screens and on the HUD; never call it from a screen.
