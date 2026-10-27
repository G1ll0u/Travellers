package com.jubitus.travellers.combat.ai;

import com.jubitus.travellers.debug.profile.MovementProfiler;
import com.jubitus.travellers.pathing.TravellerNavigateGround;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.follow.formations.WorkerAttitude;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import javax.annotation.Nullable;

/** Where the archer stands, steps and faces while aiming: kiting, HOLD tether, blind repositioning. */
final class ArcherMovement {

    private static final double APPROACH_SPEED = 0.34D; // base speed when relocating for LOS
    private static final int REPATH_INTERVAL_TICKS = 10; // how often a blind archer re-picks where to go
    private static final double DIRECT_LEG_BLOCKS = 8.0D;
    // --- HOLD attitude: near-frozen archer, only a small last-resort sidestep to clear a shot ---
    private static final float HOLD_SIDESTEP_DAMPEN = 0.4f;
    // Strafing knobs
    private static final int STRAFE_MIN_TICKS = 14;
    private static final int STRAFE_MAX_TICKS = 28;
    private static final float STRAFE_INPUT = 0.25f;      // [-1..1] input strength
    private static final int BACKSTEP_INTERVAL = 30;     // try at most once every 1.5s
    private static final float BACKSTEP_SPEED = 0.22f;   // gentle backward slide

    private final EntityTraveller mob;
    private final BowShotLine shotLine;
    private final double minDist;
    private final double maxDist;
    private int strafeDir = 1;     // +1 right, -1 left
    private int strafeTicks = 0;   // time left for current strafe direction
    private int backstepTicker = 0;
    private int repathCooldown = 0;
    private double aimX;
    private double aimZ;

    ArcherMovement(EntityTraveller mob, BowShotLine shotLine, double minDist, double maxDist) {
        this.mob = mob;
        this.shotLine = shotLine;
        this.minDist = minDist;
        this.maxDist = maxDist;
    }

    static int randBetween(EntityTraveller mob, int a, int b) {
        return a + mob.getRNG().nextInt(Math.max(1, b - a + 1));
    }

    void start() {
        strafeDir = mob.getRNG().nextBoolean() ? 1 : -1;
        strafeTicks = randBetween(mob, STRAFE_MIN_TICKS, STRAFE_MAX_TICKS);
        backstepTicker = 0;
        repathCooldown = 0;
    }

    /** HOLD archers and escort archers shoot from where they stand (an escort never walks away from its leader). */
    boolean standsGround() {
        if (!(mob instanceof EntityTravellerWorker)) return false;
        EntityTravellerWorker w = (EntityTravellerWorker) mob;
        return w.getCachedAttitude() == WorkerAttitude.HOLD || w.getEscortedLeader() != null;
    }

    /**
     * HOLD archers stand their ground - no kiting, no distance-band drift, no periodic backstep
     * (that's what would eventually walk one off the edge of a tower). If a blocked shot can't be
     * lofted over the blocker either, a small last-resort sidestep is still allowed, but only while
     * it stays within the same tether {@link EntityAIAttackMeleeCustom} uses to let HOLD melee break
     * rank ({@code COMBAT.holdBreakRadius}) - once at the edge of it, the archer just holds and waits.
     */
    void applyHoldMovement(boolean needsClearing) {
        if (needsClearing && withinHoldTether()) {
            if (shotLine.sidestepDir() != 0) strafeDir = shotLine.sidestepDir();
            float mult = TravellersConfig.COMBAT.rangedBlockedSidestepSpeedMultiplier * HOLD_SIDESTEP_DAMPEN;
            mob.getMoveHelper().strafe(0.0F, STRAFE_INPUT * (float) strafeDir * mult);
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
     * Preferred standing distance from the target: a configurable point between min and max range,
     * rather than hugging either edge.
     */
    double preferredDistance() {
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
    void hardFaceTarget(EntityLivingBase target, float maxYawStep, float maxPitchStep, float headLimit,
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
    void applyKitingMovement(double dist, boolean blocked) {
        float forward;
        float sideways;

        if (blocked) {
            // Commit to whichever side clears the shot fastest (see #findBlockingSidestepDir) instead
            // of the usual periodic left/right flip, and move with more urgency.
            if (shotLine.sidestepDir() != 0) strafeDir = shotLine.sidestepDir();
            forward = 0.0f;
            sideways = STRAFE_INPUT * (float) strafeDir * TravellersConfig.COMBAT.rangedBlockedSidestepSpeedMultiplier;
        } else {
            // Flip strafe direction periodically
            if (--strafeTicks <= 0) {
                strafeTicks = randBetween(mob, STRAFE_MIN_TICKS, STRAFE_MAX_TICKS);
                strafeDir = -strafeDir;
            }

            // Backstep gently when a bit too close (but not close enough for melee handoff)
            if (++backstepTicker >= BACKSTEP_INTERVAL) {
                backstepTicker = 0;
                if (dist < minDist * 1.08) {
                    mob.getMoveHelper().strafe(-BACKSTEP_SPEED, 0.0F);
                    setAimMoveSpeed(1.0f);
                    return;
                }
            }

            double preferred = preferredDistance();
            double tolerance = (maxDist - minDist) * 0.1;
            forward = 0.12f; // tiny forward bias to avoid dead stops
            if (dist > preferred + tolerance) forward = 0.28f;       // drift back in before hitting max range
            else if (dist < preferred - tolerance) forward = -0.18f; // ease off before hitting min range

            sideways = STRAFE_INPUT * (float) strafeDir;
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
     * lowering the bow. Straight steering, never A*: the aim point (the target, or a peek spot beside
     * it) is re-picked every {@link #REPATH_INTERVAL_TICKS}; a refused corridor leaves the archer in
     * place until the next pick, and a target it never gets a shot at is dropped by the no-shot timer.
     */
    void repositionForLOS(EntityLivingBase tgt, double d) {
        mob.setSprinting(false);
        if (repathCooldown-- <= 0) {
            repathCooldown = REPATH_INTERVAL_TICKS;
            MovementProfiler.searchAvoided(mob);
            if (d > maxDist * 0.9) {
                aimX = tgt.posX;
                aimZ = tgt.posZ;
            } else {
                // Peek to a side of the target rather than walking straight into it.
                double side = (mob.getRNG().nextBoolean() ? +1 : -1);
                double ox = tgt.posX - mob.posX;
                double oz = tgt.posZ - mob.posZ;
                double len = Math.sqrt(ox * ox + oz * oz);
                if (len > 1e-4) {
                    ox /= len;
                    oz /= len;
                }
                aimX = tgt.posX + (-oz * 1.5 * side);
                aimZ = tgt.posZ + (ox * 1.5 * side);
            }
        }
        if (mob.getNavigator() instanceof TravellerNavigateGround) {
            ((TravellerNavigateGround) mob.getNavigator()).steerDirectLegCapped(aimX, aimZ, DIRECT_LEG_BLOCKS,
                    APPROACH_SPEED * TravellersConfig.COMBAT.rangedNoLosSpeedMultiplier);
        }
    }

    private float rotlerp(float current, float target, float maxStep) {
        float delta = MathHelper.wrapDegrees(target - current);
        if (delta > maxStep) delta = maxStep;
        if (delta < -maxStep) delta = -maxStep;
        return current + delta;
    }
}
