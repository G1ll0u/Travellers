package com.jubitus.travellers.world.zone.duty;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.ai.EntityAIBase;

/**
 * Always-present, internally-gated task: does nothing unless the worker is on duty and has actually
 * arrived (not mid-travel there - the travel AI owns that phase). Delegates to whichever
 * {@link WorkerDutyBehavior} matches its {@code dutyKind} (see {@link DutyDefs}). One task shared by
 * every duty, not one task per duty kind, so a second future duty needs no new AI wiring here.
 */
public class EntityAIWorkerDuty extends EntityAIBase {

    private final EntityTravellerWorker worker;
    private WorkerDutyBehavior running;

    public EntityAIWorkerDuty(EntityTravellerWorker worker) {
        this.worker = worker;
        this.setMutexBits(1);
    }

    @Override
    public boolean shouldExecute() {
        return worker.isOnDuty() && !worker.isGoingToDuty();
    }

    @Override
    public boolean shouldContinueExecuting() {
        return shouldExecute();
    }

    @Override
    public void updateTask() {
        // A worker on duty is in "defensive" posture - the same passive stance a camping traveller
        // takes: proactive combat AI (EntityAIDefendIfClose, EntityAIDefendOwnerProactively) is
        // suppressed, only direct retaliation still fires. Re-asserted every tick, cleared in
        // resetTask (mirrors EntityAICampStay's own use of this flag).
        worker.setCampingPassive(true);

        WorkerDutyBehavior behavior = DutyDefs.get(worker.getDutyKind());
        running = behavior;
        if (behavior != null) behavior.tick(worker);
    }

    @Override
    public void resetTask() {
        worker.setCampingPassive(false);
        if (worker.isSleepingInBed()) worker.wakeUpTraveller();
        if (running != null) running.stop(worker);
        running = null;
    }
}
