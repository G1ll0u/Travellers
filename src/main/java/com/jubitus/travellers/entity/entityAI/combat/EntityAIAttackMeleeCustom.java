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

    private static final float CHASE_BOOST = 1.28f;
    private static final float FIGHT_FACTOR = 1.08f;
    private static final float LERP = 0.55f;

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

    public EntityAIAttackMeleeCustom(EntityCreature creature, double speedIn, boolean useLongMemory, double extraReach) {
        super(creature, speedIn, useLongMemory);
        this.setMutexBits(1 | 2);
        this.extraReach = Math.max(0.0, extraReach);

        double min = Math.max(0.0, TravellersModConfig.rangedMinDist);
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
        this.attacker.setSprinting(true);
        this.attacker.setAIMoveSpeed(aiSpeedNow);
    }

    @Override
    public void resetTask() {
        super.resetTask();
        this.attacker.setSprinting(false);
        aiSpeedNow = -1f;
        repathCooldown = 0;
    }

    @Override
    public void updateTask() {
        EntityLivingBase tgt = attacker.getAttackTarget();
        if (tgt == null || !tgt.isEntityAlive()) {
            attacker.setSprinting(false);
            return;
        }

        // Toujours regarder la cible proprement
        attacker.getLookHelper().setLookPositionWithEntity(tgt, 30.0F, 30.0F);

        double d2 = attacker.getDistanceSq(tgt);
        double base = this.attacker
                .getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED)
                .getAttributeValue();

        boolean veryClose = isTouchingForMelee(tgt);
        boolean closing = d2 > meleeHoldSq;

        // Repath agressif et régulier
        if (--repathCooldown <= 0) {
            repathCooldown = closing ? REPATH_INTERVAL_FAR : REPATH_INTERVAL_NEAR;
            attacker.getNavigator().tryMoveToEntityLiving(tgt, 1.0D);
        }

        float desired;
        if (closing) {
            desired = (float) (base * CHASE_BOOST);
        } else {
            // Même en “fight range”, on garde un peu de pression
            desired = (float) (base * FIGHT_FACTOR);
        }

        if (veryClose) {
            // Évite le stop complet au corps-à-corps
            desired = Math.max(desired, (float) (base * 0.96D));
        }

        if (aiSpeedNow < 0f) aiSpeedNow = (float) base;
        aiSpeedNow = lerp(aiSpeedNow, desired, LERP);

        if (aiSpeedNow > 0.38f) aiSpeedNow = 0.38f;

        this.attacker.setAIMoveSpeed(aiSpeedNow);
        this.attacker.setSprinting(closing);

        // On n'appelle plus super.updateTask() pour éviter le comportement vanilla trop haché
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

        if (gapXZSq <= reachSq && this.attackTick <= 0) {
            this.attackTick = ATTACK_COOLDOWN_TICKS;
            this.attacker.swingArm(EnumHand.MAIN_HAND);
            this.attacker.attackEntityAsMob(target);

            // On ne clear jamais le path ici
            attacker.getNavigator().tryMoveToEntityLiving(target, 1.0D);
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
        return attacker.world.isAreaLoaded(attacker.getPosition(), radiusBlocks)
                && attacker.world.isAreaLoaded(tgt.getPosition(), radiusBlocks);
    }
}