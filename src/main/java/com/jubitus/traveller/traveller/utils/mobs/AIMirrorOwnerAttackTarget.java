package com.jubitus.traveller.traveller.utils.mobs;

import com.jubitus.traveller.traveller.entityAI.EntityTravellerDog;
import net.minecraft.entity.ai.EntityAITarget;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import javax.annotation.Nullable;

public class AIMirrorOwnerAttackTarget extends EntityAITarget {
    private final EntityTravellerDog dog;

    public AIMirrorOwnerAttackTarget(EntityTravellerDog dog) {
        super(dog, false);
        this.dog = dog;
        this.setMutexBits(1);
    }

    @Override
    public boolean shouldExecute() {
        EntityLivingBase owner = dog.getOwner();
        if (owner == null) return false;

        EntityLivingBase ownerTgt = ownersAttackTarget(owner);
        if (ownerTgt == null) return false;
        if (!EntityTravellerDog.canAttackSameAsOwner(dog, ownerTgt)) return false;

        return dog.getAttackTarget() != ownerTgt;
    }

    @Override
    public void startExecuting() {
        EntityLivingBase owner = dog.getOwner();
        if (owner == null) return;

        EntityLivingBase ownerTgt = ownersAttackTarget(owner);
        if (ownerTgt != null && EntityTravellerDog.canAttackSameAsOwner(dog, ownerTgt)) {
            dog.setAttackTarget(ownerTgt);
            dog.setSitting(false);
            dog.getNavigator().clearPath();
        }
        super.startExecuting();
    }

    @Override
    public boolean shouldContinueExecuting() {
        EntityLivingBase owner = dog.getOwner();
        EntityLivingBase tgt = dog.getAttackTarget();
        if (owner == null || tgt == null || !tgt.isEntityAlive()) return false;

        EntityLivingBase ownerTgt = ownersAttackTarget(owner);
        return ownerTgt == tgt || owner.getDistance(tgt) < 24.0F;
    }

    @Nullable
    private EntityLivingBase ownersAttackTarget(EntityLivingBase owner) {
        // Only EntityLiving (not plain EntityLivingBase) has getAttackTarget() in 1.12
        return (owner instanceof EntityLiving)
                ? ((EntityLiving) owner).getAttackTarget()
                : null;
    }
}
