package com.jubitus.travellers.entity.entityAI.combat;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.utils.mobs.lists.TravellerBlacklist;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAIAttackMelee;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;

public class EntityAIAttackMeleeCustom extends EntityAIAttackMelee {
    // Tuning knobs
    private static final float CHASE_BOOST = 1.22f;  // while closing
    private static final float FIGHT_FACTOR = 1.00f; // while trading
    private static final float LERP = 0.55f;         // smoothing factor

    private final double extraReach; // in blocks

    // Melee gating thresholds (squared), computed once
    private final double meleeCommitSq;
    private final double meleeHoldSq;

    // Smooth chase speed control
    private float aiSpeedNow = -1f;

    public EntityAIAttackMeleeCustom(EntityCreature creature, double speedIn, boolean useLongMemory, double extraReach) {
        super(creature, speedIn, useLongMemory);
        this.setMutexBits(1 | 2);
        this.extraReach = Math.max(0.0, extraReach);

        double min = Math.max(0.0, TravellersModConfig.rangedMinDist);
        double commit = Math.max(0.0, min - 0.75); // start melee
        double hold   = Math.max(0.0, min - 0.25); // keep melee
        this.meleeCommitSq = commit * commit;
        this.meleeHoldSq   = hold   * hold;
    }


    @Override
    public boolean shouldExecute() {
        EntityLivingBase tgt = attacker.getAttackTarget();
        if (tgt == null || !tgt.isEntityAlive()) return false;

        // Player: require LOS or nearby to even start melee
        if (tgt instanceof EntityPlayer) {
            EntityPlayer p = (EntityPlayer) tgt;
            boolean canSee = attacker.getEntitySenses().canSee(p);
            double d2 = attacker.getDistanceSq(p);
            boolean near = d2 <= 16.0D; // 4 blocks radius

            if (!canSee && !near) return false;
        }

        // must be pathable to start melee
        if (attacker.getNavigator().getPathToEntityLiving(tgt) == null) {
            return false;
        }

        if (attacker instanceof EntityTraveller) {
            EntityTraveller t = (EntityTraveller) attacker;
            if (t.hasBowInBackpackPublic()) {
                double d2 = attacker.getDistanceSq(tgt);
                if (d2 <= meleeCommitSq) {
                    return super.shouldExecute();
                }
                return false; // let ranged own it
            }
        }
        return super.shouldExecute();
    }

    @Override
    public boolean shouldContinueExecuting() {
        EntityLivingBase tgt = attacker.getAttackTarget();
        if (tgt == null || !tgt.isEntityAlive()) return false;

        // If we have no current path AND cannot compute a new one, stop melee
        if (attacker.getNavigator().noPath()
                && attacker.getNavigator().getPathToEntityLiving(tgt) == null) {
            return false;
        }

        // Player-only rule: must be seen OR nearby
        if (tgt instanceof EntityPlayer) {
            EntityPlayer p = (EntityPlayer) tgt;
            boolean canSee = attacker.getEntitySenses().canSee(p);
            double d2 = attacker.getDistanceSq(p);
            boolean near = d2 <= 16.0D;

            if (!canSee && !near) {
                return false; // lost track of player
            }

            if (attacker instanceof EntityTraveller) {
                if (shouldForgivePlayerChaseForAI(attacker, p)) return false;
            }
        } else if (attacker.getAttackTarget() instanceof EntityPlayer) {
            // safety, in case cast path above: we already handled player case
            EntityPlayer p = (EntityPlayer) attacker.getAttackTarget();
            if (shouldForgivePlayerChaseForAI(attacker, p)) return false;
        }
        if (attacker instanceof EntityTraveller) {
            EntityTraveller t = (EntityTraveller) attacker;
            TravellerBlacklist bl = t.getAggroBlacklist(); // make it public/protected or add a helper
            if (bl != null && bl.isBlacklisted(tgt)) {
                attacker.setAttackTarget(null);
                attacker.getNavigator().clearPath();
                attacker.setSprinting(false);
                return false;
            }
        }
        if (attacker instanceof EntityTraveller) {
            EntityTraveller t = (EntityTraveller) attacker;
            if (t.hasBowInBackpackPublic()) {
                double d2 = attacker.getDistanceSq(tgt);
                if (d2 > meleeHoldSq) {
                    return false; // drop melee so ranged can resume
                }
            }
        }

        return super.shouldContinueExecuting();
    }

    @Override
    public void startExecuting() {
        super.startExecuting();

        // Initialize internal speed towards base (no instant sprint)
        double base = this.attacker
                .getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED)
                .getAttributeValue();

        aiSpeedNow = (float) base;
        this.attacker.setSprinting(false);
        this.attacker.setAIMoveSpeed(aiSpeedNow);
    }

    @Override
    public void resetTask() {
        super.resetTask();
        this.attacker.setSprinting(false);
        aiSpeedNow = -1f;
    }

    @Override
    public void updateTask() {
        super.updateTask();

        EntityLivingBase tgt = attacker.getAttackTarget();
        if (tgt == null) {
            attacker.setSprinting(false);
            return;
        }

        double d2 = attacker.getDistanceSq(tgt);
        double base = this.attacker
                .getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED)
                .getAttributeValue();

        // If we have no path, don't sprint and gently drop back to base speed
        if (attacker.getNavigator().noPath()) {
            if (aiSpeedNow < 0f) aiSpeedNow = (float) base;
            aiSpeedNow = lerp(aiSpeedNow, (float) base, LERP);
            this.attacker.setAIMoveSpeed(aiSpeedNow);
            this.attacker.setSprinting(false);
            return;
        }

        // Desired speed:
        // - If outside hold band: run a bit faster to close
        // - Inside hold band: drop near base so trading looks natural
        float desired;
        if (d2 > meleeHoldSq) {
            desired = (float) (base * CHASE_BOOST);  // run while closing
        } else {
            desired = (float) (base * FIGHT_FACTOR); // calm while in melee
        }

        if (aiSpeedNow < 0f) aiSpeedNow = (float) base;
        aiSpeedNow = lerp(aiSpeedNow, desired, LERP);

        // absolute ceiling
        if (aiSpeedNow > 0.35f) aiSpeedNow = 0.35f;

        this.attacker.setAIMoveSpeed(aiSpeedNow);

        // Sprint visual only when actually closing AND we have a path
        boolean closing = d2 > meleeHoldSq;
        this.attacker.setSprinting(closing);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    @Override
    protected void checkAndPerformAttack(EntityLivingBase target, double ignoredDistToCenterSq) {
        double gapSq = gapToAABBSq(this.attacker, target.getEntityBoundingBox()); // 3D gap
        double reachSq = this.getAttackReachSqr(target);
        if (gapSq <= reachSq && this.attackTick <= 0) {
            this.attackTick = 20;
            this.attacker.swingArm(EnumHand.MAIN_HAND);
            this.attacker.attackEntityAsMob(target);
        }
    }

    @Override
    protected double getAttackReachSqr(EntityLivingBase target) {
        AxisAlignedBB bb = target.getEntityBoundingBox();
        double wX = bb.maxX - bb.minX;
        double wZ = bb.maxZ - bb.minZ;
        double targetRadius = 0.5D * Math.max(wX, wZ);
        double myReach = (this.attacker.width * 0.5D) + 0.7D;
        double total = targetRadius + myReach + this.extraReach;
        return total * total;
    }

    private static double gapToAABBSq(Entity e, AxisAlignedBB bb) {
        double px = e.posX, py = e.posY + (e.height * 0.5D), pz = e.posZ;
        double dx = (px < bb.minX) ? (bb.minX - px) : (px > bb.maxX ? px - bb.maxX : 0.0D);
        double dy = (py < bb.minY) ? (bb.minY - py) : (py > bb.maxY ? py - bb.maxY : 0.0D);
        double dz = (pz < bb.minZ) ? (bb.minZ - pz) : (pz > bb.maxZ ? pz - bb.maxZ : 0.0D);
        return dx * dx + dy * dy + dz * dz;
    }

    private boolean shouldForgivePlayerChaseForAI(EntityCreature attacker, EntityPlayer p) {
        EntityTraveller t = (EntityTraveller) attacker;
        boolean playerCalm = t.lastPlayerAggroAgeTicks(p) > TravellersModConfig.playerCalmForgiveTicks;
        boolean fleeingLongEnough = (t.ticksExisted - t.lastLOSorCloseTick) > TravellersModConfig.playerFleeForgiveTicks;
        return playerCalm && fleeingLongEnough;
    }
}
