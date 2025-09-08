package com.jubitus.traveller.traveller.entityAI;

import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAITarget;
import net.minecraft.util.math.AxisAlignedBB;

import java.util.List;

public class TravellerHurtByTarget extends EntityAITarget {
    private final EntityCreature owner;
    private final boolean callForHelp;
    private final double assistRadius;
    private int revengeTimerOld;

    public TravellerHurtByTarget(EntityCreature owner, boolean callForHelp, double assistRadius) {
        super(owner, false); // not checkSight
        this.owner = owner;
        this.callForHelp = callForHelp;
        this.assistRadius = assistRadius;
        this.setMutexBits(1); // same as vanilla
    }

    @Override
    public boolean shouldExecute() {
        int revengeTimer = this.owner.getRevengeTimer();
        EntityLivingBase revengeTarget = this.owner.getRevengeTarget();

        // changed since last tick?
        return revengeTarget != null && revengeTimer != this.revengeTimerOld && revengeTarget.isEntityAlive();
    }

    @Override
    public void startExecuting() {
        EntityLivingBase attacker = this.owner.getRevengeTarget();
        this.taskOwner.setAttackTarget(attacker);
        this.revengeTimerOld = this.owner.getRevengeTimer();

        if (this.callForHelp && attacker != null) {
            double r = this.assistRadius;
            AxisAlignedBB box = new AxisAlignedBB(
                    owner.posX - r, owner.posY - 8, owner.posZ - r,
                    owner.posX + r, owner.posY + 8, owner.posZ + r);

            // Only notify other travellers
            List<EntityTraveller> allies = owner.world.getEntitiesWithinAABB(EntityTraveller.class, box);
            for (EntityTraveller ally : allies) {
                if (ally == owner) continue;
                if (ally.getAttackTarget() != null) continue;
                if (ally.isOnSameTeam(attacker)) continue;

                ally.setRevengeTarget(attacker);
                ally.setAttackTarget(attacker);
            }
        }

        super.startExecuting();
    }
}
