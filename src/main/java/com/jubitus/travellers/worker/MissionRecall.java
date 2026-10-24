package com.jubitus.travellers.worker;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;

/** Calling a hired worker back during a mission's outbound leg (recruiting, sale/donation, Get Equipment). */
public final class MissionRecall {

    private MissionRecall() {
    }

    public static boolean isRecallable(EntityTravellerWorker w) {
        return (w.recruit.getTour() != null && !w.recruit.getTour().isReturning())
                || w.delivery.isOutbound() || w.trips.isFetchingEquipment();
    }

    public static boolean isRecallable(VirtualTravellerRecord r) {
        return (r.recruitTour != null && !r.recruitTour.isReturning())
                || "TO_VILLAGE".equals(r.workerMode)
                || (r.goOrderActive && "GET_EQUIPMENT".equals(r.workerMode));
    }

    /** Loaded side; the offscreen side is {@code VirtualTravellerManager.recallMission}. */
    public static void recall(EntityTravellerWorker w) {
        w.recruit.recall();
        w.delivery.recall();
        w.trips.recallGetEquip();
    }
}
