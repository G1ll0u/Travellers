# Hired-worker orders and recruiting

- A recruiting tour is one `RecruitTour` object moved between the worker and its record (`VirtualSnapshot.applyToWorker` adopts it unconditionally); arrive / wait / leave decisions live in it, shared by loaded (`RecruitMission`) and offscreen (`VirtualWorkerMissions`) code.
- The purse (`unit/WorkerPurse`) belongs to the worker, not to a mission; a worker carrying money is never dismissed; tour and purse leave a dying worker before the bed-respawn snapshot.
- A recruited loaded traveller changes kind through virtualize → `VirtualTravellerManager.recruitRecord` → `materializeSoon`; never spawn a second body with its UUID.
- Whether a mission can be called back, and how: `MissionRecall` only (loaded), `VirtualTravellerManager.recallMission` (offscreen).
- A transport worker back with goods is handled only by `DeliveryJob.offerPickup` (take-only pickup screen, owner only); the job ends when the cargo is empty on close, never by forcing items back.
