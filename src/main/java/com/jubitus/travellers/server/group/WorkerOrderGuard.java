package com.jubitus.travellers.server.group;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerRecord;

/**
 * Single authoritative "can this hired worker take a new player order?" predicate, shared by every
 * order entry point (individual Go, leader Go, Get-Equipment, and all group/leader reassignments).
 * <p>
 * A worker that is busy on a mission - transport/sale/donation ({@code TO_VILLAGE}/{@code RETURNING},
 * including waiting for pickup), recruiting, or fetching equipment ({@code GET_EQUIPMENT}) - must not
 * receive any of those orders, whether it is currently loaded or virtual. Both sides read from the
 * same source of truth: the loaded entity's {@link EntityTravellerWorker.WorkerMode} (via its
 * mission predicates) and the virtual record's {@code workerMode} string, so the answer never depends
 * on whether the player happens to be near the worker.
 * <p>
 * A pending "Go" order (loaded {@code goOrderActive} / record {@code IDLE} mode) is deliberately NOT
 * busy: re-targeting a Go, or grouping a worker that is merely walking to a point, is allowed.
 */
public final class WorkerOrderGuard {

    private WorkerOrderGuard() {
    }

    public static boolean isBusy(EntityTravellerWorker w) {
        return w.isJobActive() || w.isWaitingForPickup() || w.isOnRecruitMission();
    }

    public static boolean isBusy(VirtualTravellerRecord r) {
        return "WORKER".equals(r.entityClass) && !"IDLE".equals(r.workerMode);
    }
}
