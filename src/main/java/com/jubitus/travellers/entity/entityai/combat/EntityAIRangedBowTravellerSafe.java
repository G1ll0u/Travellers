package com.jubitus.travellers.entity.entityai.combat;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.config.TravellersDerivedConfig;

import com.jubitus.travellers.entity.entityai.follow.formations.WorkerAttitude;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.combat.lists.TravellerBlacklist;
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
 * Behaviour, in order of priority each tick:
 * - The bow is drawn (real vanilla setActiveHand state, via {@link EntityTraveller#setAimingBow}) for
 * the entire time this AI is running, LOS or not - so a shot can go out the instant LOS returns and
 * so third-party mods that react to "using an item" (ambient bow-pull sounds, the bow's own string
 * texture) see the traveller as actually drawing, not just posing.
 * - With LOS: kite to hold a preferred distance band between min/max range. A friendly blocking the
 * shot line is first handled "for free" by lofting a high arc over them ({@link #canLoftOverBlocker}
 * picks the other root of the same ballistic equation used to aim); only when no arc can clear it
 * (e.g. a low ceiling) does the archer physically sidestep to open the angle.
 * - Without LOS: reposition toward the target faster than normal (a short grace period first, to
 * avoid twitchy repathing on brief flicker), still fully drawn and ready.
 * - HOLD-attitude workers ({@link #isHoldAttitude}) skip kiting/backstep/distance-drift entirely and
 * hold their ground - the lofted shot covers most blocked-line cases without moving at all, and the
 * rare last-resort sidestep is small and tethered to the worker's own formation slot. This is what
 * lets a player park an archer group on a tower without them wandering off the edge.
 * <p>
 * Min/max range gates hand-off to melee (too close) or target drop (too far) - both stay outside
 * this class, driven by {@link EntityTraveller#getAttackTarget()} range checks elsewhere.
 */
public class EntityAIRangedBowTravellerSafe extends EntityAIBase {

    private static final double BLOCK_WIDTH = 0.75D; // shot safety tube
    // --- accuracy tuning ---
    private static final float ARROW_VEL = 3.2F;     // was 2.3F
    private static final float INACC_NEAR = 1.5F;    // close-range spread
    private static final float INACC_FAR = 3.1F;    // long-range spread
    private static final double LEAD_FACTOR = 0.85;  // 0.7–1.0; how hard to lead target
    private static final int LOS_REACQUIRE_GRACE_TICKS = 6; // brief hold-still before committing to reposition
    private static final double APPROACH_SPEED = 0.34D; // base speed when relocating for LOS
    private static final int REPATH_INTERVAL_TICKS = 10; // throttle blind-repositioning pathfinding calls
    private static final int BLOCK_CHECK_INTERVAL_TICKS = 3; // throttle the ally-in-the-way entity scan
    private static final int AIM_SETTLE_MIN_TICKS = 3;
    private static final int AIM_SETTLE_MAX_TICKS = 6;
    // --- lofted ("en cloche") shots over a blocking ally ---
    private static final double LOB_CLEARANCE_MARGIN = 0.6D; // extra headroom above the blocker's AABB
    private static final float LOB_INACCURACY_BONUS = 1.4F;  // a lofted shot is harder to land than a flat one
    // Ballistic "clear the blocker" solutions run from a flat arc up to near-vertical mortar fire as
    // the blocker gets closer - past this launch angle it stops reading as an arc and starts reading
    // as a rain of arrows, so it's treated the same as "no arc can clear this" (falls back to sidestep).
    private static final double LOB_MAX_LAUNCH_ANGLE_DEG = 55.0D;
    // --- HOLD attitude: near-frozen archer, only a small last-resort sidestep to clear a shot ---
    private static final float HOLD_SIDESTEP_DAMPEN = 0.4f;
    // Brief lowered-bow window right after firing, so the string/nock visibly resets and redraws per
    // shot (like a skeleton) instead of staying frozen at full draw for the whole engagement. Short
    // enough that it's always well clear of shootIntervalTicks (min 10) before the next shot is due.
    private static final int BOW_RELAX_TICKS = 8;
    // Lead tuning
    private static final double LEAD_GAIN_LATERAL = 1.18; // 1.10–1.25: sideways runners
    private static final double LEAD_GAIN_RADIAL = 1.06; // 1.00–1.10: toward/away bias
    private static final double RELEASE_LATENCY_TICKS = 2.0; // add ~2 ticks to time-of-flight
    private static final int LEAD_ITERS = 3; // 2→3 helps convergence for long shots
    private final EntityTraveller mob;
    private final TravellerBlacklist blacklist;
    private final double minDist;
    private final double maxDist;
    private final double minDistSq;
    private final double maxDistSq;
    private final double meleeHandOffSq; // slightly > minDistSq recommended
    private final int shootIntervalTicks; // cadence
    // Strafing knobs (feel free to tune)
    private final float strafeSpeed = 0.32f;     // 0.28–0.40 feels good in 1.12
    private final int strafeMinTicks = 14;
    private final int strafeMaxTicks = 28;
    private final float strafeInput = 0.25f;      // [-1..1] input strength
    private final int backstepInterval = 30;     // try at most once every 1.5s
    private final float backstepSpeed = 0.22f;   // gentle backward slide
    private final boolean ghostEquipBow;
    private int coolDown;
    private int aimWarmup;
    private int strafeDir = 1;     // +1 right, -1 left
    private int strafeTicks = 0;   // time left for current strafe direction
    private int backstepTicker = 0;
    private int steadyTicksLeft = 0;
    private int noLOSTicks = 0;
    private int repathCooldown = 0;
    private boolean wasSeeing = false;
    // Cached result of the last blocking-ally scan (see #tickShotBlocked)
    private int blockCheckCooldown = 0;
    private int blockedSidestepDir = 0; // 0 = clear, else the direction to sidestep to clear the shot
    private int bowRelaxTicksLeft = 0; // >0 while the bow is deliberately let down after a shot
    @Nullable
    private Entity blockedByEntity = null; // the ally currently in the shot line, if any
    private float lobPitchDeg = 0f; // launch pitch (MC convention: negative = up) of the last validated lofted shot
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
        this.blacklist = TravellersDerivedConfig.TRAVELLER_BLACKLIST;
        this.minDist = minDist;
        this.maxDist = maxDist;
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
        if (TravellersDerivedConfig.isPeacefulBlocked(mob.world)) return false;
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
        // <-- do NOT require LOS here: an archer should keep closing/repositioning toward a target
        // it can't currently see, not give up the moment LOS blips.
    }

    @Override
    public boolean shouldContinueExecuting() {
        EntityLivingBase tgt = mob.getAttackTarget();
        if (tgt == null || !tgt.isEntityAlive()) return false;
        double d2 = mob.getDistanceSq(tgt);
        if (d2 > maxDistSq) return false;
        return !(d2 < meleeHandOffSq); // likewise, don't require LOS
    }

    @Override
    public void startExecuting() {
        steadyTicksLeft = 0;
        aimWarmup = randBetween(AIM_SETTLE_MIN_TICKS, AIM_SETTLE_MAX_TICKS);
        strafeDir = mob.getRNG().nextBoolean() ? 1 : -1;
        strafeTicks = randBetween(strafeMinTicks, strafeMaxTicks);
        backstepTicker = 0;
        noLOSTicks = 0;
        repathCooldown = 0;
        blockCheckCooldown = 0;
        blockedSidestepDir = 0;
        bowRelaxTicksLeft = 0;
        wasSeeing = false;

        mob.getNavigator().clearPath(); // we'll handle micro-movement
        ensureBowInHand();
        mob.setAimingBow(true);
        mob.setSprinting(false);
        mob.delaySwordHideVisual();
    }

    @Override
    public void resetTask() {
        mob.setAimingBow(false);
        mob.setSprinting(false);
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

        // Bow stays drawn for the whole time this AI runs, LOS or not - see class javadoc - except for
        // a brief relax right after a shot (see #performShot), so the string/nock visibly cycles per
        // shot like a skeleton instead of staying permanently at full draw.
        ensureBowInHand();
        if (bowRelaxTicksLeft > 0) {
            bowRelaxTicksLeft--;
        } else if (!mob.isAimingBow()) {
            mob.setAimingBow(true);
        }

        // Readiness (cooldown) isn't gated on LOS: an archer that's been blind for a while but had
        // its cadence timer run out can fire the instant it sees the target again, no extra wait.
        if (coolDown > 0) coolDown--;

        boolean see = mob.getEntitySenses().canSee(tgt);
        double d2 = mob.getDistanceSq(tgt);
        double d = Math.sqrt(d2);

        if (see) {
            noLOSTicks = 0;
            if (!wasSeeing) {
                // Just regained LOS: give the aim a short beat to settle before firing.
                aimWarmup = Math.max(aimWarmup, randBetween(AIM_SETTLE_MIN_TICKS, AIM_SETTLE_MAX_TICKS));
            }
            wasSeeing = true;

            mob.setSprinting(false);

            boolean blocked = tickShotBlocked(tgt);
            // A blocked shot isn't necessarily a dead end: try lofting an arc over the blocker first
            // (free - no movement needed, so it's also what keeps a HOLD archer from having to break
            // rank at all). Only fall back to physically sidestepping when no arc can clear it.
            boolean lofted = blocked && canLoftOverBlocker(tgt);
            boolean needsClearing = blocked && !lofted;

            // Face the actual angle we're about to shoot at - flat at the target normally, tilted up
            // to match the arc when lofting, so the pose never disagrees with the arrow's path.
            hardFaceTarget(tgt, 48.0F, 36.0F, 75.0F, lofted ? lobPitchDeg : null);

            if (isHoldAttitude()) {
                applyHoldMovement(needsClearing);
            } else {
                applyKitingMovement(d, needsClearing);
            }

            if (needsClearing) {
                return; // movement above is already biased toward clearing the shot line
            }
            if (aimWarmup > 0) {
                aimWarmup--;
                return;
            }
            if (coolDown > 0) {
                return;
            }
            if (d2 < minDistSq || d2 > maxDistSq) return;

            boolean targetMoving = Math.abs(tgt.posX - tgt.prevPosX) + Math.abs(tgt.posZ - tgt.prevPosZ) > 0.02;

            // Only steady briefly if stationary AND already in the preferred band.
            // Outside the band, keep moving; inside, 1-tick micro-settle for a cleaner shot.
            double preferred = preferredDistance();
            double band = (maxDist - minDist) * 0.15;
            boolean inBand = d >= preferred - band && d <= preferred + band;

            if (!targetMoving && inBand) {
                if (steadyTicksLeft <= 0) {
                    steadyTicksLeft = 1;
                    mob.getMoveHelper().strafe(0f, 0f);
                    return;
                } else {
                    steadyTicksLeft--;
                }
            } else {
                steadyTicksLeft = 0; // no steady while closing or target moving
            }

            performShot(tgt, d, lofted);
            coolDown = shootIntervalTicks;
            mob.setAimingBow(false);
            bowRelaxTicksLeft = BOW_RELAX_TICKS;
            return;
        }

        // --- NO LOS ---
        wasSeeing = false;
        noLOSTicks++;
        hardFaceTarget(tgt, 32.0F, 22.0F, 75.0F, null);

        // HOLD attitude: never actually walk to chase LOS back (see class javadoc for what does
        // stay active - kiting/sidestep while visible are untouched, only this blind pursuit stops).
        if (noLOSTicks < LOS_REACQUIRE_GRACE_TICKS || isHoldAttitude()) {
            // Short grace: hold still briefly (foliage flicker, a door swinging shut, etc.) rather
            // than immediately committing to a reposition.
            mob.getNavigator().clearPath();
            mob.getMoveHelper().strafe(0, 0);
            return;
        }

        repositionForLOS(tgt, d);
    }

    private boolean isHoldAttitude() {
        return mob instanceof EntityTravellerWorker
                && ((EntityTravellerWorker) mob).getCachedAttitude() == WorkerAttitude.HOLD;
    }

    /**
     * HOLD archers stand their ground - no kiting, no distance-band drift, no periodic backstep
     * (that's what would eventually walk one off the edge of a tower). If a blocked shot can't be
     * lofted over the blocker either, a small last-resort sidestep is still allowed, but only while
     * it stays within the same tether {@link EntityAIAttackMeleeCustom} uses to let HOLD melee break
     * rank ({@code COMBAT.holdBreakRadius}) - once at the edge of it, the archer just holds and waits.
     */
    private void applyHoldMovement(boolean needsClearing) {
        if (needsClearing && withinHoldTether()) {
            if (blockedSidestepDir != 0) strafeDir = blockedSidestepDir;
            float mult = TravellersConfig.COMBAT.rangedBlockedSidestepSpeedMultiplier * HOLD_SIDESTEP_DAMPEN;
            mob.getMoveHelper().strafe(0.0F, strafeInput * (float) strafeDir * mult);
            setAimMoveSpeed(mult);
        } else {
            mob.getMoveHelper().strafe(0.0F, 0.0F);
            setAimMoveSpeed(0.0f);
        }
    }

    private boolean withinHoldTether() {
        if (!(mob instanceof EntityTravellerWorker)) return true;
        EntityTravellerWorker w = (EntityTravellerWorker) mob;
        if (!w.hasFollowerSlotGoal()) return true;
        double dx = mob.posX - w.getFollowerSlotGoalX();
        double dz = mob.posZ - w.getFollowerSlotGoalZ();
        double r = TravellersConfig.COMBAT.holdBreakRadius;
        return dx * dx + dz * dz <= r * r;
    }

    /**
     * Whether a high-arc trajectory can clear the current blocking ally ({@link #blockedByEntity})
     * without physically moving. Cheap on purpose - shares the same throttle as the blocker scan
     * itself ({@link #tickShotBlocked}), so this only runs a handful of times a second even with a
     * large HOLD archer group.
     */
    private boolean canLoftOverBlocker(EntityLivingBase target) {
        Entity blocker = blockedByEntity;
        if (blocker == null) return false;

        double sx = mob.posX, sy = mob.posY + mob.getEyeHeight(), sz = mob.posZ;
        double bx = target.posX, by = target.posY + target.getEyeHeight() * 0.5, bz = target.posZ;
        double fx = bx - sx, fy = by - sy, fz = bz - sz;
        double xh = Math.sqrt(fx * fx + fz * fz);
        if (xh < 1.0E-6D) return false;

        double g = 0.05D, v = ARROW_VEL, v2 = v * v;
        double disc = v2 * v2 - g * (g * xh * xh + 2.0D * fy * v2);
        if (disc <= 0.0D) return false; // target out of ballistic range for this speed
        double tanHigh = (v2 + Math.sqrt(disc)) / (g * xh);

        double angleDeg = Math.toDegrees(Math.atan(tanHigh));
        if (angleDeg > LOB_MAX_LAUNCH_ANGLE_DEG) return false; // would read as a mortar, not an arc

        // Blocker's position projected onto the shot line.
        double ux = fx / xh, uz = fz / xh;
        double distAlong = (blocker.posX - sx) * ux + (blocker.posZ - sz) * uz;
        if (distAlong <= 0.2D || distAlong >= xh - 0.2D) return false; // not really between shooter and target

        double clearance = tanHigh * distAlong
                - g * distAlong * distAlong * (1.0D + tanHigh * tanHigh) / (2.0D * v2);
        double arcY = sy + clearance;

        double requiredY = blocker.getEntityBoundingBox().maxY + LOB_CLEARANCE_MARGIN;
        if (arcY < requiredY) return false;

        double midX = sx + fx * (distAlong / xh) * 0.5D;
        double midZ = sz + fz * (distAlong / xh) * 0.5D;
        boolean clear = columnClear(blocker.posX, blocker.posZ, arcY) && columnClear(midX, midZ, arcY);
        if (clear) lobPitchDeg = (float) -angleDeg; // MC pitch: negative looks up
        return clear;
    }

    /** Short vertical scan for a solid ceiling around the arc's height at one XZ point. */
    private boolean columnClear(double x, double z, double y) {
        BlockPos base = new BlockPos(x, y, z);
        for (int dy = 0; dy <= 2; dy++) {
            if (mob.world.getBlockState(base.up(dy)).getMaterial().blocksMovement()) return false;
        }
        return true;
    }

    /**
     * Preferred standing distance from the target: a configurable point between min and max range,
     * rather than hugging either edge.
     */
    private double preferredDistance() {
        return minDist + (maxDist - minDist) * MathHelper.clamp(TravellersConfig.COMBAT.rangedPreferredDistFactor, 0.0f, 1.0f);
    }

    /**
     * Force the travellers to face the target smoothly but authoritatively.
     * This avoids LookHelper being overridden by other AIs in the same tick.
     *
     * @param target       the entity to face
     * @param maxYawStep   max degrees of yaw change per tick
     * @param maxPitchStep max degrees of pitch change per tick
     * @param headLimit    max head/body divergence in degrees
     * @param pitchOverrideDeg if non-null, aim this pitch instead of the flat line to the target -
     *                          used for a lofted shot so the pose visibly matches the arc it's about
     *                          to fire, rather than looking level while the arrow leaves upward.
     */
    private void hardFaceTarget(EntityLivingBase target, float maxYawStep, float maxPitchStep, float headLimit,
                                 @Nullable Float pitchOverrideDeg) {
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
        float desiredPitch = pitchOverrideDeg != null
                ? pitchOverrideDeg
                : (float) (-(Math.atan2(dy, horiz) * (180D / Math.PI)));

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
        mob.noteHeadWriter("bow");
    }

    /**
     * Holds the preferred distance band while circling the target, and sidesteps to clear a blocked
     * shot line instead of just freezing.
     */
    private void applyKitingMovement(double dist, boolean blocked) {
        float forward;
        float sideways;

        if (blocked) {
            // Commit to whichever side clears the shot fastest (see #findBlockingSidestepDir) instead
            // of the usual periodic left/right flip, and move with more urgency.
            if (blockedSidestepDir != 0) strafeDir = blockedSidestepDir;
            forward = 0.0f;
            sideways = strafeInput * (float) strafeDir * TravellersConfig.COMBAT.rangedBlockedSidestepSpeedMultiplier;
        } else {
            // Flip strafe direction periodically
            if (--strafeTicks <= 0) {
                strafeTicks = randBetween(strafeMinTicks, strafeMaxTicks);
                strafeDir = -strafeDir;
            }

            // Backstep gently when a bit too close (but not close enough for melee handoff)
            if (++backstepTicker >= backstepInterval) {
                backstepTicker = 0;
                if (dist < minDist * 1.08) {
                    mob.getMoveHelper().strafe(-backstepSpeed, 0.0F);
                    setAimMoveSpeed(1.0f);
                    return;
                }
            }

            double preferred = preferredDistance();
            double tolerance = (maxDist - minDist) * 0.1;
            forward = 0.12f; // tiny forward bias to avoid dead stops
            if (dist > preferred + tolerance) forward = 0.28f;       // drift back in before hitting max range
            else if (dist < preferred - tolerance) forward = -0.18f; // ease off before hitting min range

            sideways = strafeInput * (float) strafeDir;
        }

        mob.getMoveHelper().strafe(forward, sideways);
        setAimMoveSpeed(blocked ? TravellersConfig.COMBAT.rangedBlockedSidestepSpeedMultiplier : 1.0f);
    }

    private void setAimMoveSpeed(float multiplier) {
        double base = mob.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue();
        float aimMove = (float) (base * 0.90f * multiplier);
        double absCeil = 0.26D * multiplier;
        if (aimMove > absCeil) aimMove = (float) absCeil;
        mob.setAIMoveSpeed(aimMove);
    }

    /**
     * Reposition toward the target while blind, faster than the normal aiming pace, without ever
     * lowering the bow. Repathing is throttled (rather than reissued every tick) since a blind
     * archer's target point barely changes tick to tick and pathfinding isn't free at 100+ scale.
     */
    private void repositionForLOS(EntityLivingBase tgt, double d) {
        mob.setSprinting(false);

        if (repathCooldown > 0) {
            repathCooldown--;
            return;
        }
        repathCooldown = REPATH_INTERVAL_TICKS;

        double speed = APPROACH_SPEED * TravellersConfig.COMBAT.rangedNoLosSpeedMultiplier;

        if (d > maxDist * 0.9) {
            mob.getNavigator().tryMoveToEntityLiving(tgt, speed);
            return;
        }

        // Peek to a side of the target rather than walking straight into it.
        double side = (mob.getRNG().nextBoolean() ? +1 : -1);
        double ox = tgt.posX - mob.posX;
        double oz = tgt.posZ - mob.posZ;
        double len = Math.sqrt(ox * ox + oz * oz);
        if (len > 1e-4) {
            ox /= len;
            oz /= len;
        }
        double px = tgt.posX + (-oz * 1.5 * side);
        double pz = tgt.posZ + (ox * 1.5 * side);
        BlockPos peek = new BlockPos(px, tgt.posY, pz);
        mob.getNavigator().tryMoveToXYZ(peek.getX() + 0.5D, peek.getY(), peek.getZ() + 0.5D, speed);
    }

    /**
     * Throttled wrapper around {@link #findBlockingSidestepDir}: the ally-in-the-way scan is an
     * entity AABB query, cheap once but not worth repeating every single tick for every archer.
     */
    private boolean tickShotBlocked(EntityLivingBase target) {
        if (blockCheckCooldown > 0) {
            blockCheckCooldown--;
            return blockedSidestepDir != 0;
        }
        blockCheckCooldown = BLOCK_CHECK_INTERVAL_TICKS;
        blockedSidestepDir = findBlockingSidestepDir(target);
        return blockedSidestepDir != 0;
    }

    /**
     * Scans for a protected ally (traveller or blacklisted entity) standing in the shot line.
     *
     * @return 0 if clear, otherwise the strafe direction (+1/-1) that widens the angle away from the
     * blocker fastest.
     */
    private int findBlockingSidestepDir(EntityLivingBase target) {
        blockedByEntity = null;

        Vec3d from = new Vec3d(mob.posX, mob.posY + mob.getEyeHeight(), mob.posZ);
        Vec3d to = new Vec3d(target.posX, target.posY + target.getEyeHeight() * 0.5, target.posZ);

        if (from.squareDistanceTo(to) < 1.0E-6D) return 0;

        // Broad phase: bigger swept volume
        AxisAlignedBB swept = new AxisAlignedBB(
                Math.min(from.x, to.x), Math.min(from.y, to.y), Math.min(from.z, to.z),
                Math.max(from.x, to.x), Math.max(from.y, to.y), Math.max(from.z, to.z)
        ).grow(1.2D, 0.6D, 1.2D); // wider and taller than before

        List<Entity> list = mob.world.getEntitiesWithinAABBExcludingEntity(mob, swept);
        if (list.isEmpty()) return 0;

        double dx = to.x - from.x, dz = to.z - from.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        double lx = (len > 1e-6) ? -dz / len : 0.0;
        double lz = (len > 1e-6) ? dx / len : 0.0;

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
                blockedByEntity = e;
                // Which side of the shot line the blocker sits on; step to the opposite side to
                // widen the angle away from it as fast as possible.
                double ex = e.posX - from.x, ez = e.posZ - from.z;
                double lateral = ex * lx + ez * lz;
                return lateral >= 0 ? -1 : 1;
            }
        }
        return 0;
    }

    private void performShot(EntityLivingBase target, double distance, boolean lofted) {
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

        // Final elevation: the low arc by default, or the high ("en cloche") arc when lofting over a
        // blocking ally - same quadratic, just the other root.
        double v2 = v * v;
        double disc = (v2 * v2) - g * (g * xh * xh + 2.0D * fy * v2);
        if (disc > 0.0D && xh > 1.0e-6D) {
            double root = Math.sqrt(disc);
            double tanTheta = lofted ? (v2 + root) / (g * xh) : (v2 - root) / (g * xh);
            if (lofted) {
                // Guard against the lead-adjusted aim point pushing past what was actually validated
                // as "an arc, not a mortar" in canLoftOverBlocker (which checked the raw target point).
                double tanCap = Math.tan(Math.toRadians(LOB_MAX_LAUNCH_ANGLE_DEG));
                if (tanTheta > tanCap) tanTheta = tanCap;
            }
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
        if (lofted) inaccuracy += LOB_INACCURACY_BONUS; // an arcing shot is harder to land cleanly

        // Fire
        arrow.shoot(fx, aimY, fz, ARROW_VEL, inaccuracy);

        // Player-damage scaling (including the owner-specific friendly-fire rate) is applied
        // uniformly in TravellerFriendlyFireHandler based on whoever the arrow actually hits —
        // "target" here is only the intended target, which can differ from the real victim
        // (e.g. the owner standing in the shot line), so scaling it here would silently miss that case.
        int powerLevel = net.minecraft.enchantment.EnchantmentHelper.getEnchantmentLevel(
                net.minecraft.init.Enchantments.POWER, resolveActualBow());
        double desiredFinal = (TravellersConfig.COMBAT.rangedBaseDamage
                + powerLevel * TravellersConfig.COMBAT.rangedPowerDamagePerLevel)
                * TravellersConfig.COMBAT.rangedDamageMultiplier;

        double speed = Math.sqrt(arrow.motionX * arrow.motionX
                + arrow.motionY * arrow.motionY
                + arrow.motionZ * arrow.motionZ);

        if (speed < 1.0e-6) speed = 1.0; // safety

        arrow.setDamage(desiredFinal / speed);

        // Also kill crit randomness (optional but recommended for "consistent")
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

    private ItemStack resolveActualBow() {
        ItemStack hand = mob.getHeldItemMainhand();
        if (!hand.isEmpty() && hand.getItem() instanceof ItemBow && !isGhostBow(hand)) return hand;
        return mob.findBowInBackpackPublic();
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
