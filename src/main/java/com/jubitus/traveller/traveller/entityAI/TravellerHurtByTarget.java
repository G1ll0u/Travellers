package com.jubitus.traveller.traveller.entityAI;

import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAITarget;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.EnumDifficulty;

import java.util.List;

public class TravellerHurtByTarget extends EntityAITarget {
    private final EntityCreature owner;
    private final boolean callForHelp;
    private final double assistRadius;
    private int revengeTimerOld;

    public TravellerHurtByTarget(EntityCreature owner, boolean callForHelp, double assistRadius) {
        super(owner, false); // don't require LOS for the trigger
        this.owner = owner;
        this.callForHelp = callForHelp;
        this.assistRadius = assistRadius;
        this.setMutexBits(1); // vanilla convention for target AIs
    }

    @Override
    public boolean shouldExecute() {
        if (owner.world.isRemote) return false;

        if (owner.getLastDamageSource() == null) return false;

        final int revengeTimer = owner.getRevengeTimer();
        EntityLivingBase attacker = owner.getRevengeTarget();
        if (attacker == null || !attacker.isEntityAlive()) return false;

        // Never retaliate vs Travellers, creative/spectator already handled
        if (attacker instanceof EntityTraveller) return false;

        // Ignore the FIRST 3 HITS from a PLAYER (per-player, with a short decay window)
        if (attacker instanceof EntityPlayer && owner instanceof EntityTraveller) {
            EntityTraveller t = (EntityTraveller) owner;
            EntityPlayer p = (EntityPlayer) attacker;

            if (t.notePlayerHitAndIsStillGrace(p)) {
                // swallow this retaliation tick so we don't aggro
                this.revengeTimerOld = revengeTimer;
                owner.setRevengeTarget(null); // clear so we don't re-trigger next tick
                return false;
            }
        }

        return revengeTimer != this.revengeTimerOld;
    }



    @Override
    public void startExecuting() {
        EntityLivingBase attacker = this.owner.getRevengeTarget();

        // Safety: if attacker is a traveller, do nothing
        if (attacker instanceof EntityTraveller) {
            this.revengeTimerOld = this.owner.getRevengeTimer();
            // Clear revenge so we don't re-trigger next tick
            owner.setRevengeTarget(null);
            return;
        }

        this.taskOwner.setAttackTarget(attacker);
        this.revengeTimerOld = this.owner.getRevengeTimer();

        if (this.callForHelp && attacker != null && attacker.isEntityAlive()) {
            double r = this.assistRadius;
            AxisAlignedBB box = new AxisAlignedBB(
                    owner.posX - r, owner.posY - 8, owner.posZ - r,
                    owner.posX + r, owner.posY + 8, owner.posZ + r);

            List<EntityTraveller> allies = owner.world.getEntitiesWithinAABB(EntityTraveller.class, box);
            for (EntityTraveller ally : allies) {
                if (ally == owner) continue;
                if (!ally.isEntityAlive()) continue;

                // Never assist against another traveller
                if (attacker instanceof EntityTraveller) continue;

                // Skip if ally can't/shouldn't attack
                if (ally.world.getDifficulty() == EnumDifficulty.PEACEFUL) continue;

                EntityLivingBase cur = ally.getAttackTarget();
                boolean shouldRetarget = (cur == null || !cur.isEntityAlive());

                if (!shouldRetarget) {
                    double dNew = ally.getDistanceSq(attacker);
                    double dCur = ally.getDistanceSq(cur);
                    if (dNew + 6.0 < dCur) shouldRetarget = true;
                    if (ally.isOnSameTeam(cur)) shouldRetarget = true;
                }

                if (shouldRetarget) {
                    ally.setAttackTarget(attacker);
                    ally.getNavigator().clearPath();
                }
            }
        }

        super.startExecuting();
    }
}
