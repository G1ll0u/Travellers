package com.jubitus.traveller.traveller.entityAI;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import java.util.Random;

public class EntityAIRoamInsideVillage extends EntityAIBase {
    private final EntityTraveller mob;
    private final double roamSpeed;         // slow speed inside village (e.g., 0.23)
    private final int minPause;             // ticks
    private final int maxPause;             // ticks
    private final int minStay;              // how long to hang out before leaving (ticks)
    private final int maxStay;

    private BlockPos village;               // current village center (cached)
    private BlockPos roamTarget;            // current small local target
    private int pauseTicks = 0;
    private int timeInVillage = 0;
    private int stayDuration; // <-- new


    public EntityAIRoamInsideVillage(EntityTraveller mob,
                                     double roamSpeed,
                                     int minPause, int maxPause,
                                     int minStay, int maxStay) {
        this.mob = mob;
        this.roamSpeed = roamSpeed;
        this.minPause = minPause;
        this.maxPause = maxPause;
        this.minStay = minStay;
        this.maxStay = maxStay;
        this.setMutexBits(1); // MOVE
    }

    @Override
    public boolean shouldExecute() {
        // Don’t re-enter roaming while departing
        if (mob.getDepartCooldownTicks() > 0) return false;
        if (mob.isReadyToDepart()) return false;

        BlockPos near = mob.getNearestVillage();
        if (near == null) return false;

        // Optional: only roam if the nearest village IS our current target stop.
        // This prevents “sticky” idling when we’ve already switched the target to the next stop.
        BlockPos target = mob.getTargetVillage();
        if (target == null || !near.equals(target)) return false;

        return mob.getDistanceSq(near) <= (EntityTraveller.VILLAGE_NEAR * EntityTraveller.VILLAGE_NEAR);
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (mob.getDepartCooldownTicks() > 0) return false;
        if (!mob.isVillageIdling()) return false;

        BlockPos near = mob.getNearestVillage();
        return near != null &&
                mob.getDistanceSq(near) <= (EntityTraveller.VILLAGE_NEAR * EntityTraveller.VILLAGE_NEAR);
    }



    @Override
    public void startExecuting() {
        this.village = mob.getNearestVillage(); // same as before
        this.timeInVillage = 0;
        this.pauseTicks = 0;
        this.roamTarget = null;
        this.stayDuration = minStay + mob.getRNG().nextInt(Math.max(1, maxStay - minStay + 1));
        mob.setVillageIdling(true);
        mob.setReadyToDepart(false); // make sure
        mob.setRoamTarget(null);
    }

    @Override
    public void updateTask() {
        timeInVillage++;
        mob.setVillageIdleTicks(timeInVillage);   // keep a counter if you use it elsewhere
// Leave after the chosen duration — but do NOT pick the next village here.
// Simply mark that we're done idling; the route manager will advance.
        if (timeInVillage >= stayDuration) {
            mob.setVillageIdling(false);
            mob.setReadyToDepart(true);   // signal the route code to advance
            mob.getNavigator().clearPath();
            return;
        }


        // If paused, just count down
        if (pauseTicks > 0) {
            pauseTicks--;
            return;
        }

        // Need a new local roam target?
        if (roamTarget == null || mob.getDistanceSqToCenter(roamTarget) < 2.0) {
            roamTarget = pickLocalSpotInsideVillage(this.village, EntityTraveller.VILLAGE_RADIUS - 4);
            mob.setRoamTarget(roamTarget);        // <--- expose to entityAI
            if (roamTarget != null) {
                mob.getNavigator().tryMoveToXYZ(
                        roamTarget.getX() + 0.5, roamTarget.getY(), roamTarget.getZ() + 0.5, this.roamSpeed
                );
            } else {
                // Couldn't find a spot; short pause then retry
                pauseTicks = 20 + mob.getRNG().nextInt(20);
                return;
            }
        }

        // Reached target? Take a little break & pick a new one next time
        if (roamTarget != null && mob.getDistanceSqToCenter(roamTarget) <= 2.0) {
            roamTarget = null;
            pauseTicks = minPause + mob.getRNG().nextInt(Math.max(1, maxPause - minPause + 1));
        }

        // Occasionally repush path to keep it fresh (no stalls)
        if (mob.ticksExisted % 20 == 0 && roamTarget != null) {
            mob.getNavigator().tryMoveToXYZ(
                    roamTarget.getX() + 0.5, roamTarget.getY(), roamTarget.getZ() + 0.5, this.roamSpeed
            );
        }
    }

    // ---- helpers ----

    /**
     * Pick a random standable spot within 'radius' of 'center', biasing toward loaded & valid positions.
     */
    private BlockPos pickLocalSpotInsideVillage(BlockPos center, int radius) {
        World w = mob.world;
        Random r = mob.getRNG();

        for (int tries = 0; tries < 15; tries++) {
            double angle = r.nextDouble() * Math.PI * 2;
            double dist = 6 + r.nextDouble() * (radius - 6); // avoid hugging center too tightly
            int x = center.getX() + (int) Math.round(Math.cos(angle) * dist);
            int z = center.getZ() + (int) Math.round(Math.sin(angle) * dist);

            BlockPos guess = new BlockPos(x, center.getY(), z);
            BlockPos surface = findSurfaceY(guess);
            if (surface != null && isAreaLoaded(surface, 1) && isStandable(surface)) {
                return surface;
            }
        }
        return null;
    }

    private BlockPos findSurfaceY(BlockPos guess) {
        // Same surface finder as in your travel AI (reuse that if you already have it)
        if (!isAreaLoaded(guess, 1)) return null;
        int top = mob.world.getHeight(guess).getY();
        if (top <= 0) return null;
        BlockPos pos = new BlockPos(guess.getX(), top, guess.getZ());
        for (int i = 0; i < 8; i++) {
            if (isStandable(pos)) return pos;
            pos = pos.down();
        }
        return isStandable(pos) ? pos : null;
    }

    private boolean isStandable(BlockPos feet) {
        IBlockState below = mob.world.getBlockState(feet.down());
        IBlockState at = mob.world.getBlockState(feet);
        IBlockState head = mob.world.getBlockState(feet.up());
        return (below.isFullCube() || below.isSideSolid(mob.world, feet.down(), EnumFacing.UP))
                && (at.getMaterial().isReplaceable() || at.getCollisionBoundingBox(mob.world, feet) == null)
                && (head.getMaterial().isReplaceable() || head.getCollisionBoundingBox(mob.world, feet.up()) == null);
    }

    private boolean isAreaLoaded(BlockPos pos, int radius) {
        if (!(mob.world instanceof WorldServer)) return mob.world.isAreaLoaded(pos, radius);
        WorldServer ws = (WorldServer) mob.world;
        int minCX = (pos.getX() - radius) >> 4, maxCX = (pos.getX() + radius) >> 4;
        int minCZ = (pos.getZ() - radius) >> 4, maxCZ = (pos.getZ() + radius) >> 4;
        for (int cx = minCX; cx <= maxCX; cx++)
            for (int cz = minCZ; cz <= maxCZ; cz++)
                if (!ws.getChunkProvider().chunkExists(cx, cz)) return false;
        return true;
    }
    @Override
    public void resetTask() {
        mob.setVillageIdling(false);
        mob.setRoamTarget(null);
        this.roamTarget = null;
    }
}
