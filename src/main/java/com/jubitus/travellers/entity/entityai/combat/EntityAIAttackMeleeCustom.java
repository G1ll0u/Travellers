package com.jubitus.travellers.entity.entityai.combat;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.entityai.follow.formations.WorkerAttitude;
import com.jubitus.travellers.mount.MountMode;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAIAttackMelee;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;

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

        boolean inReach = isInReach(tgt, 1.35D);

        if (attacker instanceof EntityTraveller) {
            EntityTraveller t = (EntityTraveller) attacker;
            if (t.hasBowInBackpackPublic()) {
                double d2 = attacker.getDistanceSq(tgt);
                return d2 <= meleeCommitSq;
            }
        }

        if (inReach) return true;

        WorkerAttitude attitude = attitudeOf();
        if (attitude != WorkerAttitude.ATTACK && !withinTether(tgt)) return false;

        return isPathAreaLoaded(tgt, 24);
    }

    /** Group posture for this attacker - always ATTACK for anything that isn't a hired worker. */
    private WorkerAttitude attitudeOf() {
        return (attacker instanceof EntityTravellerWorker)
                ? ((EntityTravellerWorker) attacker).getCachedAttitude()
                : WorkerAttitude.ATTACK;
    }

    /**
     * DEFEND/HOLD tether: refuses to chase a target beyond a short leash instead of freezing in
     * place the moment it's out of strict melee reach - a HOLD archer that's rushed still needs to
     * take a few real steps to cut the intruder down (standing dead still while both sides shuffle
     * for the last half-block of reach just looks like jittering), it just returns to formation
     * afterward once the follow AI reclaims control.
     * <p>
     * DEFEND is anchored to the group's current leader/owner position (a moving anchor - the group
     * can keep advancing while still holding its ground against distant threats).
     * HOLD is anchored to the worker's OWN current formation slot (a much tighter, per-worker leash)
     * so "hold the line" archers only ever break rank far enough to deal with whoever breached it.
     */
    private boolean withinTether(EntityLivingBase tgt) {
        if (!(attacker instanceof EntityTravellerWorker)) return true;
        EntityTravellerWorker w = (EntityTravellerWorker) attacker;

        double anchorX;
        double anchorZ;
        double radius;
        if (w.getCachedAttitude() == WorkerAttitude.HOLD) {
            if (w.hasFollowerSlotGoal()) {
                anchorX = w.getFollowerSlotGoalX();
                anchorZ = w.getFollowerSlotGoalZ();
            } else {
                anchorX = attacker.posX;
                anchorZ = attacker.posZ;
            }
            radius = TravellersConfig.COMBAT.holdBreakRadius;
        } else {
            EntityLivingBase anchor = w.getEffectiveFollowTarget();
            anchorX = anchor != null ? anchor.posX : attacker.posX;
            anchorZ = anchor != null ? anchor.posZ : attacker.posZ;
            radius = TravellersConfig.COMBAT.defendTetherRadius;
        }

        double dx = tgt.posX - anchorX;
        double dz = tgt.posZ - anchorZ;
        return dx * dx + dz * dz <= radius * radius;
    }

    private boolean isInReach(EntityLivingBase tgt, double reachFudge) {
        AxisAlignedBB bb = tgt.getEntityBoundingBox();
        double gapXZSq = gapToAABB_XZ_Sq(this.attacker, bb);

        double myFeet = this.attacker.posY;
        double myHead = this.attacker.posY + this.attacker.height;
        double vGap = 0.0D;
        if (myHead < bb.minY) vGap = bb.minY - myHead;
        else if (myFeet > bb.maxY) vGap = myFeet - bb.maxY;

        double reachSq = this.getAttackReachSqr(tgt);
        return (gapXZSq <= (reachSq * reachFudge)) && (vGap <= 2.0D);
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

        WorkerAttitude attitude = attitudeOf();
        if (attitude != WorkerAttitude.ATTACK && !isInReach(tgt, 1.35D) && !withinTether(tgt)) {
            return false;
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

        // A mounted fighter that has actually closed to striking distance gets off and finishes the
        // fight on foot rather than swinging from the saddle - real melee reads as pedestrian combat
        // either way, so this buys the "cavalry charges in, dismounts to fight" read for free instead
        // of building a mounted-swing animation state. Reuses the same terrain-dismount plumbing
        // (markTerrainFallbackLed) so the rider naturally climbs back on once it walks away from here
        // after the fight, with no separate remount logic needed.
        if (inReach && TravellersConfig.COMBAT.mountedMeleeDismountEnabled && attacker instanceof EntityTraveller) {
            EntityTraveller t = (EntityTraveller) attacker;
            if (t.isMountedOnBondedMount()) {
                com.jubitus.travellers.debug.profile.MovementProfiler.ledStarted(t, com.jubitus.travellers.debug.profile.MovementAnomaly.LedReason.MELEE);
                TravellerMountSupport.setMountMode(t, MountMode.LED);
                TravellerMountSupport.markTerrainFallbackLed(t);
            }
        }

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
                    Vec3d curvedAim = mountedApproachAim(tgt, inReach);
                    if (curvedAim != null) {
                        attacker.getNavigator().tryMoveToXYZ(curvedAim.x, curvedAim.y, curvedAim.z, navMultiplier);
                    } else {
                        attacker.getNavigator().tryMoveToEntityLiving(tgt, navMultiplier);
                    }
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
        double myReach = (this.attacker.width * 0.5D) + TravellersConfig.COMBAT.meleeReachBonus;
        double total = targetRadius + myReach + this.extraReach;
        return total * total;
    }

    /**
     * A curved aim point for a mounted rider still closing on its target - riding straight at the
     * target like a pedestrian is what made mounted melee read as "just a pedestrian on a horse".
     * The curve shrinks as the gap closes (capped at a fraction of the remaining distance) so it
     * naturally funnels into strike range instead of circling forever; {@code null} once already in
     * reach (dismounting takes over from there) or for anything not mounted.
     */
    private Vec3d mountedApproachAim(EntityLivingBase tgt, boolean inReach) {
        if (inReach || !(attacker instanceof EntityTraveller)) return null;
        if (!((EntityTraveller) attacker).isMountedOnBondedMount()) return null;

        double curveMax = TravellersConfig.COMBAT.mountedMeleeApproachCurve;
        if (curveMax <= 0.0D) return null;

        double dx = tgt.posX - attacker.posX;
        double dz = tgt.posZ - attacker.posZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0E-3D) return null;

        double curve = Math.min(curveMax, dist * 0.3D);
        // Perpendicular to the approach line. Side is stable per attacker (entity ID parity) so a
        // given chase always curves the same way instead of flickering side to side every repath.
        double nx = -dz / dist;
        double nz = dx / dist;
        double side = (attacker.getEntityId() & 1) == 0 ? 1.0D : -1.0D;
        return new Vec3d(tgt.posX + nx * curve * side, tgt.posY, tgt.posZ + nz * curve * side);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
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