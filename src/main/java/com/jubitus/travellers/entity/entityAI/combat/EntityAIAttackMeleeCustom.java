package com.jubitus.travellers.entity.entityAI.combat;

import com.jubitus.travellers.config.TravellersConfig;
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

    // Chase speed (closing distance) scales above the configured in-reach fight speed by this ratio,
    // preserving the original 1.28/1.08 relationship between pursuit and fighting.
    private static final float CHASE_RATIO = 1.28f / 1.08f;
    private static final float LERP = 0.55f;

    // "Still engaged" proximity band — within this range, LOS memory refreshes even without a raytrace hit
    private static final double NEAR_RANGE = 4.0D;
    private static final double NEAR_RANGE_SQ = NEAR_RANGE * NEAR_RANGE;

    // Nouveau : on repousse activement le nav au lieu d'attendre le vanilla
    private static final int REPATH_INTERVAL_FAR = 4;
    private static final int REPATH_INTERVAL_NEAR = 2;

    // Nouveau : cadence d'attaque plus nerveuse
    private static final int ATTACK_COOLDOWN_TICKS = 24;

    private final double extraReach;

    private final double meleeCommitSq;
    private final double meleeHoldSq;

    private float aiSpeedNow = -1f;
    private int repathCooldown = 0;
    private boolean holdingPosition = false;

    public EntityAIAttackMeleeCustom(EntityCreature creature, double speedIn, boolean useLongMemory, double extraReach) {
        super(creature, speedIn, useLongMemory);
        this.setMutexBits(1 | 2);
        this.extraReach = Math.max(0.0, extraReach);

        double min = Math.max(0.0, TravellersConfig.COMBAT.rangedMinDist);
        double commit = Math.max(0.0, min - 0.75);
        double hold = Math.max(0.0, min - 0.25);
        this.meleeCommitSq = commit * commit;
        this.meleeHoldSq = hold * hold;
    }

    @Override
    public boolean shouldExecute() {
        EntityLivingBase tgt = attacker.getAttackTarget();
        if (tgt == null || !tgt.isEntityAlive()) return false;

        if (tgt instanceof EntityPlayer) {
            EntityPlayer p = (EntityPlayer) tgt;
            boolean canSee = attacker.getEntitySenses().canSee(p);
            double d2 = attacker.getDistanceSq(p);
            boolean near = d2 <= 16.0D;
            if (!canSee && !near) return false;
        }

        AxisAlignedBB bb = tgt.getEntityBoundingBox();
        double gapXZSq = gapToAABB_XZ_Sq(this.attacker, bb);

        double myFeet = this.attacker.posY;
        double myHead = this.attacker.posY + this.attacker.height;
        double vGap = 0.0D;
        if (myHead < bb.minY) vGap = bb.minY - myHead;
        else if (myFeet > bb.maxY) vGap = myFeet - bb.maxY;

        double reachSq = this.getAttackReachSqr(tgt);
        boolean inReach = (gapXZSq <= (reachSq * 1.35D)) && (vGap <= 2.0D);

        if (attacker instanceof EntityTraveller) {
            EntityTraveller t = (EntityTraveller) attacker;
            if (t.hasBowInBackpackPublic()) {
                double d2 = attacker.getDistanceSq(tgt);
                return d2 <= meleeCommitSq;
            }
        }

        if (inReach) return true;

        return isPathAreaLoaded(tgt, 24);
    }

    @Override
    public boolean shouldContinueExecuting() {
        EntityLivingBase tgt = attacker.getAttackTarget();
        if (tgt == null || !tgt.isEntityAlive()) return false;

        if (attacker instanceof EntityTraveller) {
            EntityTraveller t = (EntityTraveller) attacker;

            // Chase-memory: losing sight/proximity doesn't end the chase immediately.
            // Applies equally to players and mobs — an attacker keeps pursuing a target it can't
            // currently see for a grace window, and only truly gives up once that window expires
            // with no LOS/proximity refresh (the "stuck" detector in EntityTraveller.onUpdate()
            // still separately catches an unreachable target sooner).
            boolean canSee = attacker.getEntitySenses().canSee(tgt);
            double d2 = attacker.getDistanceSq(tgt);
            boolean near = d2 <= NEAR_RANGE_SQ;
            if (canSee || near) {
                t.combat.lastLOSorCloseTick = t.ticksExisted;
            } else if (t.ticksExisted - t.combat.lastLOSorCloseTick > TravellersConfig.COMBAT.losMemoryTicks) {
                return false;
            }

            if (tgt instanceof EntityPlayer && shouldForgivePlayerChaseForAI(attacker, (EntityPlayer) tgt)) {
                return false;
            }

            if (t.hasBowInBackpackPublic() && d2 > meleeHoldSq) {
                return false;
            }
        }

        // Ne plus couper l'AI juste parce que le nav a perdu son path un instant
        return true;
    }

    @Override
    public void startExecuting() {
        super.startExecuting();

        double base = this.attacker
                .getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED)
                .getAttributeValue();

        aiSpeedNow = (float) base;
        repathCooldown = 0;
        this.attacker.setSprinting(false);
        this.attacker.setAIMoveSpeed(aiSpeedNow);

        // Fresh chase-memory window — avoids inheriting a stale tick count from a previous, unrelated fight
        if (this.attacker instanceof EntityTraveller) {
            ((EntityTraveller) this.attacker).combat.lastLOSorCloseTick = this.attacker.ticksExisted;
        }
    }

    @Override
    public void resetTask() {
        super.resetTask();
        this.attacker.setSprinting(false);
        aiSpeedNow = -1f;
        repathCooldown = 0;
        holdingPosition = false;
    }

    @Override
    public void updateTask() {
        EntityLivingBase tgt = attacker.getAttackTarget();
        if (tgt == null || !tgt.isEntityAlive()) {
            attacker.setSprinting(false);
            return;
        }

        attacker.getLookHelper().setLookPositionWithEntity(tgt, 30.0F, 30.0F);

        double d2 = attacker.getDistanceSq(tgt);
        double base = attacker.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue();

        AxisAlignedBB bb = tgt.getEntityBoundingBox();
        double gapXZSq = gapToAABB_XZ_Sq(attacker, bb);
        double reachSq = getAttackReachSqr(tgt);
        boolean inReach = gapXZSq <= reachSq;
        boolean onCooldown = this.attackTick > 0;

        if (inReach && onCooldown) {
            // Hold position during cooldown — mirrors player behavior between hits
            if (!holdingPosition) {
                attacker.getNavigator().clearPath();
                holdingPosition = true;
            }
            if (aiSpeedNow < 0f) aiSpeedNow = (float) base;
            aiSpeedNow = lerp(aiSpeedNow, 0f, LERP);
            attacker.setAIMoveSpeed(aiSpeedNow);
        } else {
            holdingPosition = false;

            float fightSpeed = TravellersConfig.SPEED.movementSpeedWhileAttacking;
            float chaseSpeed = fightSpeed * CHASE_RATIO;
            float desired = inReach ? fightSpeed : chaseSpeed;

            // Closing in or ready to strike — approach target.
            // The multiplier passed here is re-applied every tick by EntityMoveHelper
            // (speed × MOVEMENT_SPEED attribute) for as long as this path stays active,
            // silently overriding any setAIMoveSpeed() call below — it must reflect our
            // configured desired speed, not a flat 1.0 (full base speed).
            if (--repathCooldown <= 0) {
                repathCooldown = inReach ? REPATH_INTERVAL_NEAR : REPATH_INTERVAL_FAR;
                if (CombatPathScheduler.tryAcquire(attacker.world, attacker.getEntityId(), 1)) {
                    double navMultiplier = base > 1.0E-4 ? (desired / base) : 1.0D;
                    attacker.getNavigator().tryMoveToEntityLiving(tgt, navMultiplier);
                }
            }

            if (aiSpeedNow < 0f) aiSpeedNow = (float) base;
            aiSpeedNow = lerp(aiSpeedNow, desired, LERP);
            if (aiSpeedNow > chaseSpeed) aiSpeedNow = chaseSpeed;
            attacker.setAIMoveSpeed(aiSpeedNow);
        }

        this.checkAndPerformAttack(tgt, d2);
    }

    @Override
    protected void checkAndPerformAttack(EntityLivingBase target, double ignoredDistToCenterSq) {
        AxisAlignedBB bb = target.getEntityBoundingBox();

        double gapXZSq = gapToAABB_XZ_Sq(this.attacker, bb);

        double y = this.attacker.posY;
        double yTop = this.attacker.posY + this.attacker.height;
        double vGap = 0.0D;
        if (yTop < bb.minY) vGap = bb.minY - yTop;
        else if (y > bb.maxY) vGap = y - bb.maxY;

        if (vGap > 2.0D) return;

        double reachSq = this.getAttackReachSqr(target);

        if (this.attackTick > 0) {
            this.attackTick--;
        }

        // Never actually land a hit on something we can't see (e.g. through a thin wall corner)
        if (gapXZSq <= reachSq && this.attackTick <= 0 && this.attacker.getEntitySenses().canSee(target)) {
            this.attackTick = TravellerItemDamage.computeAttackCooldownTicks(attacker);
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

        double px = attacker.posX;
        double pz = attacker.posZ;
        double dx = (px < bb.minX) ? (bb.minX - px) : (px > bb.maxX ? px - bb.maxX : 0.0D);
        double dz = (pz < bb.minZ) ? (bb.minZ - pz) : (pz > bb.maxZ ? pz - bb.maxZ : 0.0D);
        double gapXZSq = dx * dx + dz * dz;

        if (gapXZSq > (0.6D * 0.6D)) return false;

        double myFeet = attacker.posY;
        double myHead = attacker.posY + attacker.height;
        double vGap = 0.0D;
        if (myHead < bb.minY) vGap = bb.minY - myHead;
        else if (myFeet > bb.maxY) vGap = myFeet - bb.maxY;

        return vGap <= 1.5D;
    }

    private boolean shouldForgivePlayerChaseForAI(EntityCreature attacker, EntityPlayer p) {
        EntityTraveller t = (EntityTraveller) attacker;
        boolean playerCalm = t.lastPlayerAggroAgeTicks(p) > TravellersConfig.PLAYER_AGGRO.playerCalmForgiveTicks;
        boolean fleeingLongEnough = (t.ticksExisted - t.combat.lastLOSorCloseTick) > TravellersConfig.PLAYER_AGGRO.playerFleeForgiveTicks;
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
        return attacker.world.isAreaLoaded(attacker.getPosition(), radiusBlocks)
                && attacker.world.isAreaLoaded(tgt.getPosition(), radiusBlocks);
    }
}