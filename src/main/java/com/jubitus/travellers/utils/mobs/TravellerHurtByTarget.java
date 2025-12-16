package com.jubitus.travellers.utils.mobs;

import com.jubitus.travellers.entity.travellers.EntityTraveller;
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

        // NEW: if too far, consume the event so we don't retrigger forever
        if (owner.getDistanceSq(attacker) > assistRadius * assistRadius) {
            this.revengeTimerOld = revengeTimer;
            // optional, but often helps:
            owner.setRevengeTarget(null);
            return false;
        }

        if (attacker instanceof EntityTraveller && owner instanceof EntityTraveller) {
            boolean crossFaction = ((EntityTraveller) owner).isAggressiveToTravellers()
                    ^ ((EntityTraveller) attacker).isAggressiveToTravellers();
            if (!crossFaction) {
                this.revengeTimerOld = revengeTimer;
                owner.setRevengeTarget(null);
                return false;
            }
        }

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
        this.revengeTimerOld = this.owner.getRevengeTimer(); // consume by default
        if (attacker == null || !attacker.isEntityAlive()) {
            owner.setRevengeTarget(null);
            return;
        }
        if (owner.getDistanceSq(attacker) > assistRadius * assistRadius) {
            owner.setRevengeTarget(null); // optional but helps prevent re-triggers
            return;
        }
        // Safety: if attacker is a travellers, do nothing
        if (attacker instanceof EntityTraveller && !((EntityTraveller) attacker).isAggressiveToTravellers()) {
            this.revengeTimerOld = this.owner.getRevengeTimer();
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
                if (attacker instanceof EntityTraveller) continue;
                if (ally.world.getDifficulty() == EnumDifficulty.PEACEFUL) continue;

                // NEW: only assist if the attacker is within helpRange of THIS ally
                if (ally.getDistanceSq(attacker) > (assistRadius * assistRadius)) continue;

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
