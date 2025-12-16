package com.jubitus.travellers.entity.entityAI.follow;

import com.jubitus.travellers.entity.travellers.EntityTravellerDog;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public class AIFollowOwnerDirectional extends EntityAIBase {

    private final EntityTravellerDog dog;
    private final double speed;
    private final float minDist;
    private final float maxDist;

    private EntityLivingBase owner;
    private int recalcTimer = 0;
    private int stuckTimer = 0;

    public AIFollowOwnerDirectional(EntityTravellerDog dog, double speed, float minDist, float maxDist) {
        this.dog = dog;
        this.speed = speed;
        this.minDist = minDist;
        this.maxDist = maxDist;
        this.setMutexBits(3); // movement + look
    }

    @Override
    public boolean shouldExecute() {
        owner = dog.getOwner();
        if (owner == null) return false;
        return !dog.isSitting();
    }

    @Override
    public boolean shouldContinueExecuting() {
        return shouldExecute();
    }

    @Override
    public void resetTask() {
        dog.getNavigator().clearPath();
        recalcTimer = 0;
    }

    @Override
    public void updateTask() {
        if (owner == null) return;

        double dist = dog.getDistance(owner);

        // Keep reasonable spacing
        if (dist < minDist) {
            dog.getNavigator().clearPath();
            return;
        }

        // Recalculate path every few ticks
        if (--recalcTimer <= 0) {
            recalcTimer = 5;

            // --- Predict owner's movement ---
            Vec3d ownerPos = new Vec3d(owner.posX, owner.posY, owner.posZ);
            Vec3d vel = new Vec3d(
                    owner.posX - owner.prevPosX,
                    owner.posY - owner.prevPosY,
                    owner.posZ - owner.prevPosZ
            );

            // Smooth out micro-jitter
            if (vel.lengthSquared() < 0.0001) {
                vel = Vec3d.ZERO;
            } else {
                vel = vel.normalize().scale(4); // how far ahead of owner to target
            }

            Vec3d target = ownerPos.add(vel);

            dog.getNavigator().tryMoveToXYZ(target.x, target.y, target.z, speed);
        }

        // --- Rare emergency teleport (if badly stuck) ---
        if (dist > maxDist * 2) {
            stuckTimer++;
            if (stuckTimer > 15) {
                BlockPos p = new BlockPos(owner.posX, owner.posY, owner.posZ);
                dog.attemptTeleport(p.getX(), p.getY(), p.getZ());
                stuckTimer = 0;
            }
        } else {
            stuckTimer = 0;
        }

        // Look in the same direction the owner looks (nice touch)
        dog.getLookHelper().setLookPositionWithEntity(owner, 30.0f, 30.0f);
    }
}

