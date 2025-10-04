package com.jubitus.traveller.traveller.entityAI;

import com.jubitus.traveller.TravellersModConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIAttackMelee;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;

public class EntityAIAttackMeleeCustom extends EntityAIAttackMelee {
    private final double extraReach; // in blocks

    // Melee gating thresholds (squared), computed once; tweak the margins to taste.
    // commit: we only START melee when closer than rangedMinDist - 0.75
    // hold: we only KEEP melee while still within rangedMinDist - 0.25
    private final double meleeCommitSq;
    private final double meleeHoldSq;

    public EntityAIAttackMeleeCustom(EntityCreature creature, double speedIn, boolean useLongMemory, double extraReach) {
        super(creature, speedIn, useLongMemory);
        this.setMutexBits(1 | 2);
        this.extraReach = Math.max(0.0, extraReach);

        double min = Math.max(0.0, TravellersModConfig.rangedMinDist);
        double commit = Math.max(0.0, min - 0.75);   // when we’ll allow melee to start
        double hold   = Math.max(0.0, min - 0.25);   // a bit looser so we don’t ping-pong
        this.meleeCommitSq = commit * commit;
        this.meleeHoldSq   = hold   * hold;
    }

    // Distance from our XZ to the target AABB in XZ (0 if already touching)
    private static double horizontalGapSq(Entity e, AxisAlignedBB bb) {
        double dx = 0.0D, dz = 0.0D;
        if (e.posX < bb.minX) dx = bb.minX - e.posX; else if (e.posX > bb.maxX) dx = e.posX - bb.maxX;
        if (e.posZ < bb.minZ) dz = bb.minZ - e.posZ; else if (e.posZ > bb.maxZ) dz = e.posZ - bb.maxZ;
        return dx * dx + dz * dz;
    }

    @Override
    public boolean shouldExecute() {
        EntityLivingBase tgt = attacker.getAttackTarget();
        if (tgt == null || !tgt.isEntityAlive()) return false;

        if (attacker instanceof EntityTraveller) {
            EntityTraveller t = (EntityTraveller) attacker;
            if (t.hasBowInBackpackPublic()) {
                // Only START melee when well inside the min ranged distance
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

        if (attacker instanceof EntityTraveller) {
            EntityTraveller t = (EntityTraveller) attacker;
            if (t.hasBowInBackpackPublic()) {
                // KEEP melee only while we remain inside the "hold" band.
                double d2 = attacker.getDistanceSq(tgt);
                if (d2 > meleeHoldSq) {
                    return false; // drop melee so ranged can resume (kite/shoot)
                }
            }
        }
        return super.shouldContinueExecuting();
    }

    @Override
    public void startExecuting() {
        super.startExecuting();
        this.attacker.setSprinting(false); // avoid sprint spikes
        // Optional: pin melee speed so it doesn’t inherit crazy values
        // this.attacker.setAIMoveSpeed((float)TravellersModConfig.attackMovementSpeed);
    }

    @Override
    public void resetTask() {
        super.resetTask();
        this.attacker.setSprinting(false);
    }

    @Override
    public void updateTask() {
        super.updateTask();
        // vanilla sometimes toggles sprint: keep it off for consistent speed
        this.attacker.setSprinting(false);
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
}