package com.jubitus.travellers.world.zone.duty;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;

/**
 * What a worker actually does once it has arrived and is on duty at its assigned zone. One
 * implementation per duty kind (see {@link DutyDefs}), keyed by {@code EntityTravellerWorker.dutyKind}
 * rather than hardcoded per zone type - a future zone type introducing a second job slots in the
 * same way, with no change to {@link EntityAIWorkerDuty} or the worker's own tick loop.
 */
public interface WorkerDutyBehavior {

    /** Called every tick the worker is on duty and has actually arrived (not mid-travel there).
     * Implementations throttle their own real work internally. */
    void tick(EntityTravellerWorker worker);

    /** The duty task stopped (pre-empted, released, going offscreen): drop any chore mid-flight. */
    default void stop(EntityTravellerWorker worker) {
    }
}
