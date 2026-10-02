package com.jubitus.travellers.debug.path;

import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.util.math.BlockPos;

/**
 * The whole lifecycle of a {@code /travellersdebug path test} entity: watches for arrival at the
 * destination, and kills it either way past a hard time limit so a route that never converges can't
 * leave a throwaway test entity wandering forever. Mutex 0 - it must never contend with the travel
 * AI it is watching.
 */
public final class PathTestWatchdogAI extends EntityAIBase {

    private final EntityTraveller mob;
    private final BlockPos destination;
    private final double arriveDistSq;
    private final int maxTicks;
    private int ticksAlive = 0;

    public PathTestWatchdogAI(EntityTraveller mob, BlockPos destination, double arriveRadiusBlocks, int maxTicks) {
        this.mob = mob;
        this.destination = destination;
        this.arriveDistSq = arriveRadiusBlocks * arriveRadiusBlocks;
        this.maxTicks = maxTicks;
        this.setMutexBits(0);
    }

    @Override
    public boolean shouldExecute() {
        return true;
    }

    @Override
    public boolean shouldContinueExecuting() {
        return true;
    }

    @Override
    public void updateTask() {
        ticksAlive++;
        if (mob.getDistanceSqToCenter(destination) <= arriveDistSq) {
            PathTestManager.finishActiveTest(mob, "arrived at destination");
        } else if (ticksAlive >= maxTicks) {
            PathTestManager.finishActiveTest(mob, "timed out after " + (maxTicks / 20) + "s");
        }
    }
}
