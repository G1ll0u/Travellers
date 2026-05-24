package com.jubitus.travellers.entity.entityAI.choir;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.entity.ai.EntityAIBase;

/**
 * High-priority task (added at priority 0) that completely freezes a worker
 * during a choir session. Mutex bits 3 (move=1 + look=2) prevent any other
 * movement or look AI task from running while this task is active.
 *
 * The early-return in EntityTravellerWorker.onLivingUpdate() handles the
 * custom non-AI navigation code (catchup, follow mode, etc.).
 */
public final class EntityAIChoirFreeze extends EntityAIBase {

    private final EntityTravellerWorker worker;

    public EntityAIChoirFreeze(EntityTravellerWorker worker) {
        this.worker = worker;
        this.setMutexBits(3); // 1 = movement, 2 = look
    }

    @Override
    public boolean shouldExecute() {
        return worker.isChoirFreezeActive();
    }

    @Override
    public boolean shouldContinueExecuting() {
        return worker.isChoirFreezeActive();
    }

    @Override
    public void startExecuting() {
        worker.getNavigator().clearPath();
        worker.motionX = 0;
        worker.motionZ = 0;
    }

    @Override
    public void updateTask() {
        worker.getNavigator().clearPath();
        worker.motionX = 0;
        worker.motionZ = 0;
        worker.getLookHelper().setLookPosition(
                worker.choirFreezeLookX,
                worker.posY + worker.getEyeHeight(),
                worker.choirFreezeLookZ,
                10f, 10f
        );
    }
}
