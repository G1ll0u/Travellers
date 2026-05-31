package com.jubitus.travellers.entity.entityAI.combat;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.utils.mobs.lists.TravellerBlacklist;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.projectile.EntityTippedArrow;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Safety-first ranged AI for 1.12.2 travellers, with vertical aim, enforced facing, and strafing.
 * <p>
 * Features:
 * - Runs only if the travellers has a bow (in hand or backpack; will ghost-equip for visuals).
 * - Min/max range gates; hands off to melee when too close; drops target when too far.
 * - Always faces target while aiming (overrides travel-facing while in combat).
 * - Proper vertical aim; uses target eye/mid height and vanilla bow power mapping.
 * - Strafes left/right while aiming, with occasional backstep when too close.
 * - Holds fire if a Traveller or blacklisted entity is along the shot line.
 */
public class EntityAIRangedBowTravellerSafe extends EntityAIBase {

    private static final double BLOCK_WIDTH = 0.75D; // shot safety tube
    // --- accuracy tuning ---
    private static final float ARROW_VEL = 3.2F;     // was 2.3F
    private static final float INACC_NEAR = 1.5F;    // close-range spread
    private static final float INACC_FAR = 3.1F;    // long-range spread
    private static final double LEAD_FACTOR = 0.85;  // 0.7–1.0; how hard to lead target
    private static final int STEADY_TICKS = 1;     // stop strafing right before shot
    private static final int LOS_REACQUIRE_DELAY = 6; // start peeking sooner
    private static final double APPROACH_SPEED = 0.34D; // when relocating for LOS
    // Lead tuning
    private static final double LEAD_GAIN_LATERAL = 1.18; // 1.10–1.25: sideways runners
    private static final double LEAD_GAIN_RADIAL = 1.06; // 1.00–1.10: toward/away bias
    private static final double RELEASE_LATENCY_TICKS = 2.0; // add ~2 ticks to time-of-flight
    private static final int LEAD_ITERS = 3; // 2→3 helps convergence for long shotsc
    private final EntityTraveller mob;
    private final TravellerBlacklist blacklist;
    private final double minDistSq;
    private final double maxDistSq;
    private final double meleeHandOffSq; // slightly > minDistSq recommended
    private final int shootIntervalTicks; // cadence
    // Strafing knobs (feel free to tune)
    private final float strafeSpeed = 0.32f;     // 0.28–0.40 feels good in 1.12
    private final int strafeMinTicks = 14;
    private final int strafeMaxTicks = 28;
    private final float strafeInput = 0.25f;      // [-1..1] input strength
    private final double strafeAbsSpeed = 0.26D; // absolute cap while strafing (≈ skeleton pace)
    private final int backstepInterval = 30;     // try at most once every 1.5s
    private final float backstepSpeed = 0.22f;   // gentle backward slide
    private final boolean ghostEquipBow;
    private final float strafingFactor = 0.90f;
    private final double strafeAbsCeil = 0.26D;
    private int coolDown;
    private int aimWarmup;
    private int strafeDir = 1;     // +1 right, -1 left
    private int strafeTicks = 0;   // time left for current strafe direction
    private int backstepTicker = 0;
    private int steadyTicksLeft = 0;
    private int noLOSTicks = 0;
    @Nullable
    private java.util.UUID lastTargetId = null;
    private double lastVX = 0, lastVY = 0, lastVZ = 0;
    private int lastVelTick = -1000;


    public EntityAIRangedBowTravellerSafe(EntityTraveller owner,
                                          double minDist,
                                          double maxDist,
                                          int shootIntervalTicks,
                                          double meleeHandOff,
                                          boolean ghostEquipBow) {
        this.mob = owner;
        this.blacklist = TravellersModConfig.TRAVELLER_BLACKLIST;
        this.minDistSq = minDist * minDist;
        this.maxDistSq = maxDist * maxDist;
        this.meleeHandOffSq = meleeHandOff * meleeHandOff;
        this.shootIntervalTicks = Math.max(10, shootIntervalTicks);
        this.ghostEquipBow = ghostEquipBow;
        this.setMutexBits(1 | 2); // move + look
    }

    @Override
    public boolean shouldExecute() {
        if (mob.world.isRemote) return false;
        if (mob.world.getDifficulty().getId() <= 0) return false; // PEACEFUL
        if (mob.isAutoEating()) return false;

        EntityLivingBase tgt = mob.getAttackTarget();
        if (tgt == null || !tgt.isEntityAlive()) return false;
        if (!mob.hasBowAnywherePublic()) return false;

        double d2 = mob.getDistanceSq(tgt);
        if (d2 > maxDistSq) {
            // Just stop this AI; don't clear target here or the horde tracker will grab control.
            return false;
        }
        return !(d2 < meleeHandOffSq); // melee owns close range
// <-- do NOT require LOS here
    }

    @Override
    public boolean shouldContinueExecuting() {
        EntityLivingBase tgt = mob.getAttackTarget();
        if (tgt == null || !tgt.isEntityAlive()) return false;
        double d2 = mob.getDistanceSq(tgt);
        if (d2 > maxDistSq) return false;
        return !(d2 < meleeHandOffSq);// <-- likewise, don’t require LOS
    }

    @Override
    public void startExecuting() {
        steadyTicksLeft = 0;
        aimWarmup = 3 + mob.getRNG().nextInt(4); // 3–6 ticks instead of 8–13
        strafeDir = mob.getRNG().nextBoolean() ? 1 : -1;
        strafeTicks = randBetween(strafeMinTicks, strafeMaxTicks);
        backstepTicker = 0;

        mob.getNavigator().clearPath(); // we’ll handle micro-movement
        ensureBowInHand();
        mob.setAimingBow(true);
        mob.setSprinting(false);                 // <-- add: no sprint while entering ranged
        mob.delaySwordHideVisual();
    }

    @Override
    public void resetTask() {
        mob.setAimingBow(false);
        mob.setSprinting(false);                 // <-- add
        // put away ghost bow if needed
        ItemStack hand = mob.getHeldItemMainhand();
        if (!hand.isEmpty() && hand.getItem() instanceof ItemBow && isGhostBow(hand)) {
            mob.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
        }
        mob.getMoveHelper().strafe(0.0F, 0.0F);
    }

    @Override
    public void updateTask() {
        EntityLivingBase tgt = mob.getAttackTarget();
        if (tgt == null) return;

        boolean see = mob.getEntitySenses().canSee(tgt);
        double d2 = mob.getDistanceSq(tgt);
        double d = Math.sqrt(d2);

        if (see) {
            noLOSTicks = 0;
            // Aiming / strafing mode (your existing code)
            mob.setSprinting(false);
            hardFaceTarget(tgt, 48.0F, 36.0F, 75.0F);
            ensureBowInHand();
            if (!mob.isAimingBow()) mob.setAimingBow(true);
            applyStrafeMovement(d, tgt);

            if (hasBlockingEntityAlongShot(tgt)) {
                return;
            }
            if (aimWarmup > 0) {
                aimWarmup--;
                return;
            }
            if (coolDown > 0) {
                coolDown--;
                return;
            }
            if (!mob.getEntitySenses().canSee(tgt)) return;
            if (d2 < minDistSq || d2 > maxDistSq) return;
// after computing d2/d and confirming LOS/range
            boolean targetMoving = Math.abs(tgt.posX - tgt.prevPosX) + Math.abs(tgt.posZ - tgt.prevPosZ) > 0.02;

// Only steady briefly if stationary AND already in the sweet band.
// Outside the band, keep moving; inside, 1-tick micro-settle.
            double minOk = Math.sqrt(minDistSq) * 1.02;
            double maxOk = Math.sqrt(maxDistSq) * 0.98;

            if (!targetMoving && d >= minOk && d <= maxOk) {
                if (steadyTicksLeft <= 0) {
                    steadyTicksLeft = STEADY_TICKS; // now 1
                    mob.getMoveHelper().strafe(0f, 0f);
                    return;
                } else {
                    steadyTicksLeft--;
                }
            } else {
                steadyTicksLeft = 0; // no steady while closing or target moving
            }


            performShot(tgt, d);
            coolDown = shootIntervalTicks;

            return;
        }

        // --- REACQUIRE MODE (no LOS) ---
        if (++noLOSTicks >= LOS_REACQUIRE_DELAY) {
            // Stop aiming & put the bow away visually so the run looks natural
            if (mob.isAimingBow()) mob.setAimingBow(false);
            ItemStack hand = mob.getHeldItemMainhand();
            if (!hand.isEmpty() && hand.getItem() instanceof ItemBow && isGhostBow(hand)) {
                mob.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
            }

            // Try walking toward a small "peek" offset around the target
            // (slightly to the side so we don’t path straight into them)
            double side = (mob.getRNG().nextBoolean() ? +1 : -1);
            double ox = (tgt.posX - mob.posX);
            double oz = (tgt.posZ - mob.posZ);
            double len = Math.sqrt(ox * ox + oz * oz);
            if (len > 1e-4) {
                ox /= len;
                oz /= len;
            } // normalize
            // perpendicular offset ~1.5 blocks
            double px = tgt.posX + (-oz * 1.5 * side);
            double pz = tgt.posZ + (ox * 1.5 * side);
            BlockPos peek = new BlockPos(px, tgt.posY, pz);

            // If very far, just path to target; if near, path to peek
            if (d > Math.sqrt(maxDistSq) * 0.9) {
                mob.getNavigator().tryMoveToEntityLiving(tgt, APPROACH_SPEED);
            } else {
                mob.getNavigator().tryMoveToXYZ(peek.getX() + 0.5D, peek.getY(), peek.getZ() + 0.5D, APPROACH_SPEED);
            }
            // keep turning toward last known area for nice head behavior
            hardFaceTarget(tgt, 32.0F, 22.0F, 75.0F);
            float yawErr = Math.abs(MathHelper.wrapDegrees(mob.rotationYawHead - mob.renderYawOffset));
            float pitchErr = Math.abs(mob.rotationPitch);
            if (yawErr > 14.0F || pitchErr > 12.0F) {
                steadyTicksLeft = Math.max(steadyTicksLeft, 2); // give it a breath if aim just swung a lot
            }
        } else {
            // Short grace: hold still briefly to see if target reappears
            mob.getNavigator().clearPath();
            mob.getMoveHelper().strafe(0, 0);
        }
    }

    /**
     * Force the travellers to face the target smoothly but authoritatively.
     * This avoids LookHelper being overridden by other AIs in the same tick.
     *
     * @param target       the entity to face
     * @param maxYawStep   max degrees of yaw change per tick
     * @param maxPitchStep max degrees of pitch change per tick
     * @param headLimit    max head/body divergence in degrees
     */
    private void hardFaceTarget(EntityLivingBase target, float maxYawStep, float maxPitchStep, float headLimit) {
        // Vector from eyes to target mid-height
        double sx = mob.posX;
        double sy = mob.posY + mob.getEyeHeight();
        double sz = mob.posZ;
        double tx = target.posX;
        double ty = target.posY + target.getEyeHeight() * 0.5;
        double tz = target.posZ;
        double dx = tx - sx;
        double dy = ty - sy;
        double dz = tz - sz;

        double horiz = MathHelper.sqrt(dx * dx + dz * dz);
        if (horiz < 1.0E-6 && Math.abs(dy) < 1.0E-6) return;

        float desiredYaw = (float) (Math.atan2(dz, dx) * (180D / Math.PI)) - 90.0F;
        float desiredPitch = (float) (-(Math.atan2(dy, horiz) * (180D / Math.PI)));

        // Smoothly step head first
        mob.rotationYawHead = rotlerp(mob.rotationYawHead, desiredYaw, maxYawStep);
        mob.rotationPitch = rotlerp(mob.rotationPitch, desiredPitch, maxPitchStep);

        // Pull body toward head so other visuals don't fight us
        mob.rotationYaw = rotlerp(mob.rotationYaw, mob.rotationYawHead, maxYawStep);
        mob.renderYawOffset = rotlerp(mob.renderYawOffset, mob.rotationYaw, maxYawStep);

        // Clamp head/body divergence
        float diff = MathHelper.wrapDegrees(mob.rotationYawHead - mob.renderYawOffset);
        if (diff < -headLimit) mob.rotationYawHead = mob.renderYawOffset - headLimit;
        if (diff > headLimit) mob.rotationYawHead = mob.renderYawOffset + headLimit;
    }

    private void applyStrafeMovement(double dist, EntityLivingBase tgt) {

        // Flip strafe direction periodically
        if (--strafeTicks <= 0) {
            strafeTicks = randBetween(strafeMinTicks, strafeMaxTicks);
            strafeDir = -strafeDir;
        }

        // Backstep gently when a bit too close (but not close enough for melee handoff)
        if (++backstepTicker >= backstepInterval) {
            backstepTicker = 0;
            if (dist * dist < (minDistSq * 1.08)) {
                // small backwards nudge
                mob.getMoveHelper().strafe(-backstepSpeed, 0.0F);
                return;
            }
        }

        // Circle around: small forward to keep moving + lateral strafe
        float forward = 0.12f; // tiny forward bias to avoid dead stops
        float sideways = strafeInput * (float) strafeDir;

        // If we’re drifting out of ideal band, bias forward/back
        double min = Math.sqrt(minDistSq) * 1.05;
        double max = Math.sqrt(maxDistSq) * 0.95;
        if (dist < min) forward = -0.18f;    // back away a bit
        else if (dist > max) forward = 0.28f; // advance to get back in range

        // Apply and clamp speed so they don’t sprint while circling
        mob.getMoveHelper().strafe(forward, sideways);

// scale relative to base walk; keep strafing under walk pace
        double base = mob.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue();
        float strafingFactor = 0.90f;                  // 90% of walk while aiming
        float aimMove = (float) (base * strafingFactor);

// optional absolute ceiling, in case some mods bump base a lot
        double absCeil = 0.26D;
        if (aimMove > absCeil) aimMove = (float) absCeil;

        mob.setAIMoveSpeed(aimMove);
    }

    private boolean hasBlockingEntityAlongShot(EntityLivingBase target) {
        Vec3d from = new Vec3d(mob.posX, mob.posY + mob.getEyeHeight(), mob.posZ);
        Vec3d to = new Vec3d(target.posX, target.posY + target.getEyeHeight() * 0.5, target.posZ);

        if (from.squareDistanceTo(to) < 1.0E-6D) return false;

        // Broad phase: bigger swept volume
        AxisAlignedBB swept = new AxisAlignedBB(
                Math.min(from.x, to.x), Math.min(from.y, to.y), Math.min(from.z, to.z),
                Math.max(from.x, to.x), Math.max(from.y, to.y), Math.max(from.z, to.z)
        ).grow(1.2D, 0.6D, 1.2D); // wider and taller than before

        List<Entity> list = mob.world.getEntitiesWithinAABBExcludingEntity(mob, swept);
        if (list.isEmpty()) return false;

        for (Entity e : list) {
            if (!e.isEntityAlive() || e == target) continue;

            // Only care about allies / protected entities
            boolean protectedAlly =
                    (e instanceof EntityTraveller)
                            || (e instanceof EntityLivingBase && blacklist != null && blacklist.isBlacklisted(e));

            if (!protectedAlly) continue;

            // Expand their hitbox a bit to be extra safe
            AxisAlignedBB bb = e.getEntityBoundingBox().grow(0.4D, 0.5D, 0.4D);

            // Does our shot segment intersect this grown AABB?
            if (bb.calculateIntercept(from, to) != null) {
                return true;
            }
        }
        return false;
    }

    private void performShot(EntityLivingBase target, double distance) {
        World w = mob.world;
        EntityTippedArrow arrow = new EntityTippedArrow(w, mob);
        arrow.setEnchantmentEffectsFromEntity(mob, 1.0F);
        arrow.pickupStatus = EntityTippedArrow.PickupStatus.DISALLOWED;

        // Shooter eye
        final double sx = mob.posX;
        final double sy = mob.posY + mob.getEyeHeight();
        final double sz = mob.posZ;

        // Base aim (target mid)
        double bx = target.posX;
        double by = target.posY + target.getEyeHeight() * 0.5;
        double bz = target.posZ;

        // Smoothed target velocity (blocks/tick)
        Vec3d vT = getSmoothedVelocity(target);

        // Iterate lead using actual flight time from ballistic solve
        final double g = 0.05D;            // vanilla arrow gravity (~1.12)
        final double v = ARROW_VEL;
        double aimX = bx, aimY = by, aimZ = bz;

        for (int i = 0; i < LEAD_ITERS; i++) {
            // Vector from shooter to current aim
            double rx = aimX - sx, ry = aimY - sy, rz = aimZ - sz;
            double xh = Math.sqrt(rx * rx + rz * rz); // horizontal range

            // Unit forward & lateral on XZ
            double ux = (xh > 1e-6) ? rx / xh : 0.0;
            double uz = (xh > 1e-6) ? rz / xh : 0.0;
            double lx = -uz, lz = ux;

            // Decompose target horizontal velocity
            double vr = vT.x * ux + vT.z * uz; // toward/away
            double vl = vT.x * lx + vT.z * lz; // sideways

            // Gentle gains → slight over-lead predictable motion
            vr *= LEAD_GAIN_RADIAL;
            vl *= LEAD_GAIN_LATERAL;

            // Recompose gained velocity + damp vertical (jumping is noisy)
            double gvx = vr * ux + vl * lx;
            double gvz = vr * uz + vl * lz;
            double gvy = vT.y * 0.5;

            // Lower-arc ballistic solve: tanθ = (v^2 - √(v^4 - g(gx^2 + 2y v^2)))/(g x)
            double v2 = v * v;
            double disc = (v2 * v2) - g * (g * xh * xh + 2.0D * ry * v2);

            double tanTheta, cosTheta;
            if (disc > 0.0D && xh > 1.0e-6D) {
                double root = Math.sqrt(disc);
                tanTheta = (v2 - root) / (g * xh);
                cosTheta = 1.0 / Math.sqrt(1.0 + tanTheta * tanTheta);
            } else {
                // shallow fallback when too close/steep
                tanTheta = 0.06;
                cosTheta = 1.0 / Math.sqrt(1.0 + tanTheta * tanTheta);
            }

            // Actual flight time + small release latency
            double t = xh / (v * cosTheta);
            t += RELEASE_LATENCY_TICKS;
            t = Math.max(0.0, Math.min(2.0, t));

            // Predict target at that time with the gained velocity
            aimX = bx + gvx * t;
            aimY = by + gvy * t;
            aimZ = bz + gvz * t;
        }

        // Final deltas
        double fx = aimX - sx;
        double fy = aimY - sy;
        double fz = aimZ - sz;
        double xh = Math.sqrt(fx * fx + fz * fz);

        // Final lower-arc elevation
        double v2 = v * v;
        double disc = (v2 * v2) - g * (g * xh * xh + 2.0D * fy * v2);
        if (disc > 0.0D && xh > 1.0e-6D) {
            double root = Math.sqrt(disc);
            double tanTheta = (v2 - root) / (g * xh);
            aimY = tanTheta * xh;
        } else {
            aimY = fy + xh * 0.06D;
        }

        // Calm just before release (1 tick)
        mob.getMoveHelper().strafe(0.0F, 0.0F);
        double base = mob.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue();
        mob.setAIMoveSpeed((float) Math.min(base * 0.60D, 0.22D));

        // Distance-scaled inaccuracy (keeps some misses)
        double dNorm = Math.min(1.0, xh / 24.0);
        float inaccuracy = (float) (INACC_NEAR + (INACC_FAR - INACC_NEAR) * dNorm);

        // Fire
        arrow.shoot(fx, aimY, fz, ARROW_VEL, inaccuracy);

        double desiredFinal = TravellersModConfig.rangedBaseDamage * TravellersModConfig.rangedDamageMultiplier;
        if (target instanceof EntityPlayer) {
            desiredFinal *= TravellersModConfig.travellerPlayerDamageMultiplier;
        }

        double speed = Math.sqrt(arrow.motionX * arrow.motionX
                + arrow.motionY * arrow.motionY
                + arrow.motionZ * arrow.motionZ);

        if (speed < 1.0e-6) speed = 1.0; // safety

        arrow.setDamage(desiredFinal / speed);

// Also kill crit randomness (optional but recommended for “consistent”)
        arrow.setIsCritical(false);


        w.playSound(null, mob.posX, mob.posY, mob.posZ, SoundEvents.ENTITY_SKELETON_SHOOT,
                SoundCategory.HOSTILE, 1.0F, 1.0F / (mob.getRNG().nextFloat() * 0.4F + 0.8F));
        w.spawnEntity(arrow);
        mob.swingArm(EnumHand.MAIN_HAND);
    }


    private float rotlerp(float current, float target, float maxStep) {
        float delta = MathHelper.wrapDegrees(target - current);
        if (delta > maxStep) delta = maxStep;
        if (delta < -maxStep) delta = -maxStep;
        return current + delta;
    }

    private Vec3d getSmoothedVelocity(EntityLivingBase t) {
        double vx = (t.posX - t.prevPosX);
        double vy = (t.posY - t.prevPosY);
        double vz = (t.posZ - t.prevPosZ);

        java.util.UUID id = t.getUniqueID();
        if (lastTargetId != null && lastTargetId.equals(id) && mob.ticksExisted == lastVelTick + 1) {
            double a = 0.60; // 60% previous sample, smooths jitter
            vx = a * lastVX + (1.0 - a) * vx;
            vy = a * lastVY + (1.0 - a) * vy;
            vz = a * lastVZ + (1.0 - a) * vz;
        }
        lastTargetId = id;
        lastVX = vx;
        lastVY = vy;
        lastVZ = vz;
        lastVelTick = mob.ticksExisted;
        return new Vec3d(vx, vy, vz);
    }

    private boolean isGhostBow(ItemStack s) {
        return !s.isEmpty() && s.getItem() instanceof ItemBow && s.hasTagCompound() && s.getTagCompound().getBoolean("TravellerGhostBow");
    }

    private int randBetween(int a, int b) {
        return a + mob.getRNG().nextInt(Math.max(1, b - a + 1));
    }

    private void ensureBowInHand() {
        ItemStack hand = mob.getHeldItemMainhand();
        boolean holdingBow = !hand.isEmpty() && hand.getItem() instanceof ItemBow;
        if (holdingBow) return;
        if (!ghostEquipBow) return;
        ItemStack bow = mob.findBowInBackpackPublic();
        if (!bow.isEmpty()) mob.setHeldItem(EnumHand.MAIN_HAND, mob.makeGhostBow(bow));
    }


}
