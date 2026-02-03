package com.jubitus.travellers.entity.entityAI.combat;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
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
        double hold = Math.max(0.0, min - 0.25); // keep melee
        this.meleeCommitSq = commit * commit;
        this.meleeHoldSq = hold * hold;
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
            boolean near = d2 <= 16.0D;
            if (!canSee && !near) return false;
        }

        // --- compute "inReach" (same as you had) ---
        AxisAlignedBB bb = tgt.getEntityBoundingBox();
        double gapXZSq = gapToAABB_XZ_Sq(this.attacker, bb);

        double myFeet = this.attacker.posY;
        double myHead = this.attacker.posY + this.attacker.height;
        double vGap = 0.0D;
        if (myHead < bb.minY) vGap = bb.minY - myHead;
        else if (myFeet > bb.maxY) vGap = myFeet - bb.maxY;

        double reachSq = this.getAttackReachSqr(tgt);
        boolean inReach = (gapXZSq <= (reachSq * 1.35D)) && (vGap <= 2.0D);

        // --- Bow handoff logic (must happen BEFORE final returns) ---
        if (attacker instanceof EntityTraveller) {
            EntityTraveller t = (EntityTraveller) attacker;
            if (t.hasBowInBackpackPublic()) {
                double d2 = attacker.getDistanceSq(tgt);
                // If we're close enough to commit to melee, allow melee AI to start.
                // Otherwise, do NOT start melee (let ranged AI take over).
                return d2 <= meleeCommitSq;
            }
        }

        // If we are already basically in melee range, start even without a path.
        if (inReach) return true;

        // Don't compute a path here. Also avoid chunk loads by refusing to path
        // unless the area is already loaded.
        return isPathAreaLoaded(tgt, 24);

        // Let the task start; updateTask() will request paths on a cooldown.
    }


    @Override
    public boolean shouldContinueExecuting() {
        EntityLivingBase tgt = attacker.getAttackTarget();
        if (tgt == null || !tgt.isEntityAlive()) return false;

        // Only stop for "no path" if we're NOT in melee range
        if (attacker.getNavigator().noPath() && !isTouchingForMelee(tgt)) {
            // If we're not touching and also have no path, give up.
            // (updateTask will try to regain a path when appropriate)
            return false;
        }

        // ... keep your existing player rules + bow handoff rules ...
        if (tgt instanceof EntityPlayer) {
            EntityPlayer p = (EntityPlayer) tgt;
            boolean canSee = attacker.getEntitySenses().canSee(p);
            double d2 = attacker.getDistanceSq(p);
            boolean near = d2 <= 16.0D;
            if (!canSee && !near) return false;

            if (attacker instanceof EntityTraveller) {
                if (shouldForgivePlayerChaseForAI(attacker, p)) return false;
            }
        }

        if (attacker instanceof EntityTraveller) {
            EntityTraveller t = (EntityTraveller) attacker;
            if (t.hasBowInBackpackPublic()) {
                double d2 = attacker.getDistanceSq(tgt);
                if (d2 > meleeHoldSq) return false;
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
        double gapSq = gapToAABBSq(this.attacker, tgt.getEntityBoundingBox());
        double reachSq = this.getAttackReachSqr(tgt);

        if (isTouchingForMelee(tgt)) {
            attacker.getNavigator().clearPath();
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

    @Override
    protected void checkAndPerformAttack(EntityLivingBase target, double ignoredDistToCenterSq) {
        AxisAlignedBB bb = target.getEntityBoundingBox();

        // Horizontal gap only (XZ) — much closer to vanilla feel
        double gapXZSq = gapToAABB_XZ_Sq(this.attacker, bb);

        // Vertical tolerance so we can hit things slightly above/below
        // (tune 1.5–2.5; 2.0 feels good for “melee on uneven ground”)
        double y = this.attacker.posY;                 // feet Y
        double yTop = this.attacker.posY + this.attacker.height;
        double vGap = 0.0D;
        if (yTop < bb.minY) vGap = bb.minY - yTop;     // target above us
        else if (y > bb.maxY) vGap = y - bb.maxY;      // target below us

        if (vGap > 2.0D) return; // too far vertically to reasonably melee

        double reachSq = this.getAttackReachSqr(target);

        if (gapXZSq <= reachSq && this.attackTick <= 0) {
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

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private boolean isTouchingForMelee(EntityLivingBase tgt) {
        AxisAlignedBB bb = tgt.getEntityBoundingBox();

        // Horizontal gap between attacker position and target AABB
        double px = attacker.posX;
        double pz = attacker.posZ;
        double dx = (px < bb.minX) ? (bb.minX - px) : (px > bb.maxX ? px - bb.maxX : 0.0D);
        double dz = (pz < bb.minZ) ? (bb.minZ - pz) : (pz > bb.maxZ ? pz - bb.maxZ : 0.0D);
        double gapXZSq = dx * dx + dz * dz;

        // "Touching" threshold: ~0.6 blocks horizontal (very tight)
        if (gapXZSq > (0.6D * 0.6D)) return false;

        // Vertical sanity: same pit / ledge only
        double myFeet = attacker.posY;
        double myHead = attacker.posY + attacker.height;
        double vGap = 0.0D;
        if (myHead < bb.minY) vGap = bb.minY - myHead;
        else if (myFeet > bb.maxY) vGap = myFeet - bb.maxY;

        return vGap <= 1.5D; // tweak 1.0–2.0
    }

    private boolean shouldForgivePlayerChaseForAI(EntityCreature attacker, EntityPlayer p) {
        EntityTraveller t = (EntityTraveller) attacker;
        boolean playerCalm = t.lastPlayerAggroAgeTicks(p) > TravellersModConfig.playerCalmForgiveTicks;
        boolean fleeingLongEnough = (t.ticksExisted - t.lastLOSorCloseTick) > TravellersModConfig.playerFleeForgiveTicks;
        return playerCalm && fleeingLongEnough;
    }

    private static double gapToAABB_XZ_Sq(Entity e, AxisAlignedBB bb) {
        double px = e.posX;
        double pz = e.posZ;
        double dx = (px < bb.minX) ? (bb.minX - px) : (px > bb.maxX ? px - bb.maxX : 0.0D);
        double dz = (pz < bb.minZ) ? (bb.minZ - pz) : (pz > bb.maxZ ? pz - bb.maxZ : 0.0D);
        return dx * dx + dz * dz;
    }

    private boolean isPathAreaLoaded(EntityLivingBase tgt, int radiusBlocks) {
        // If either area isn't loaded, don't pathfind (prevents chunk loads from AI)
        return attacker.world.isAreaLoaded(attacker.getPosition(), radiusBlocks)
                && attacker.world.isAreaLoaded(tgt.getPosition(), radiusBlocks);
    }

}
