package com.jubitus.travellers.world.zone.duty;

import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import net.minecraft.world.WorldServer;

/**
 * The offscreen counterpart of {@link WorkerDutyBehavior}: what a worker's duty progresses toward
 * while both the worker and its assigned zone are unloaded, with no live entity to drive. One
 * implementation per duty kind (see {@link VirtualDutyDefs}), mirroring the loaded side's own
 * per-kind table so a future duty needs no new branching in either the loaded or virtual caller.
 */
public interface VirtualDutyBehavior {

    /** Called once the record has "arrived" at its duty zone (see the caller in
     * {@code VirtualTravellerManager.refreshGoal}). Implementations must never touch the world -
     * only the zone's own persisted state (e.g. a Stable's {@code StableHorseEntry} pool) - since
     * this runs precisely when nothing around the record is guaranteed to be loaded. */
    void tickVirtual(WorldServer world, VirtualTravellerRecord r, long now);
}
