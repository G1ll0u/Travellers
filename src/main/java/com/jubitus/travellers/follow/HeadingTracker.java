package com.jubitus.travellers.follow;

import com.jubitus.travellers.follow.formations.WorkerFormationSettings;
import com.jubitus.travellers.follow.formations.WorkerFormationStyle;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.MathHelper;

import java.util.*;

/**
 * A leader's smoothed formation heading and movement state: accumulates displacement over a short
 * window, decides the heading it should have, and slews the published heading toward it a little
 * every tick so a turn plays out smoothly.
 */
final class HeadingTracker {

    private static final double PLAYER_MOVE_EPSILON_SQ = 0.012D * 0.012D;
    private static final double WORKER_MOVE_EPSILON_SQ = 0.060D * 0.060D;
    private static final double HEADING_REVERSE_DOT_LIMIT = -0.82D;

    double stableDirX = 0.0D;
    double stableDirZ = 1.0D;
    // Where the windowed heading logic below wants stableDir to end up. stableDir itself is
    // never snapped straight to this - it's slewed toward it a little every tick (see
    // slewStableDirTowardTarget) so a heading change plays out as a smooth turn instead of the
    // whole group's formation snapping sideways in one instant when a window closes.
    private double targetDirX = 0.0D;
    private double targetDirZ = 1.0D;
    double leaderSpeed = 0.0D;
    boolean leaderActuallyMoving = false;
    private int leaderStoppedTicks = 0;

    private double headingAnchorX = Double.NaN;
    private double headingAnchorZ = Double.NaN;
    private int headingAccumTicks = 0;

    void update(EntityLivingBase leader, WorkerFormationSettings formationSettings) {
        boolean workerLeader = leader instanceof EntityTravellerWorker;

        double tickDx = leader.posX - leader.prevPosX;
        double tickDz = leader.posZ - leader.prevPosZ;
        double tickDistSq = tickDx * tickDx + tickDz * tickDz;

        double thresholdSq = workerLeader ? WORKER_MOVE_EPSILON_SQ : PLAYER_MOVE_EPSILON_SQ;
        boolean meaningfulTickMotion = tickDistSq > thresholdSq;

        if (Double.isNaN(headingAnchorX) || Double.isNaN(headingAnchorZ)) {
            headingAnchorX = leader.posX;
            headingAnchorZ = leader.posZ;
            headingAccumTicks = 0;
        }

        if (meaningfulTickMotion) {
            leaderSpeed = Math.sqrt(tickDistSq);
            leaderActuallyMoving = true;
            leaderStoppedTicks = 0;
        } else {
            leaderSpeed *= 0.88D;
            if (leaderSpeed < 0.02D) {
                // Worker-leaders navigate around obstacles at variable speed, so require
                // several consecutive slow ticks before declaring them stopped.
                // Players stop almost instantly so 1 tick is enough.
                int stopThreshold = workerLeader ? 8 : 1;
                leaderStoppedTicks++;
                if (leaderStoppedTicks >= stopThreshold) {
                    leaderActuallyMoving = false;
                }
            } else {
                leaderStoppedTicks = 0;
            }
        }

        headingAccumTicks++;

        double accDx = leader.posX - headingAnchorX;
        double accDz = leader.posZ - headingAnchorZ;
        double accDistSq = accDx * accDx + accDz * accDz;

        WorkerFormationStyle style = formationSettings.style != null ? formationSettings.style : WorkerFormationStyle.ADAPTIVE;
        double minAccDist;
        double minAngleRad;
        int maxAccumTicks;
        double headingBlendMin;
        double headingBlendMax;
        switch (style) {
            case COMPACT:
                // Needs a bit more distance to commit — formation is less sensitive to heading.
                // Player minAccDist is calibrated below normal walk-speed accumulation over
                // maxAccumTicks (~3.9 blocks at 4.3 blocks/s over 18 ticks) - it used to sit
                // above that (4.5), which meant a player walking at normal speed never reached
                // haveAccumulatedHeading and always fell through to the much weaker "drift
                // nudge" fallback below, so the formation only ever re-oriented once the group
                // fell behind and the leader "overshot" it (see minAccDist comments on the
                // other cases for the same reasoning).
                minAccDist      = workerLeader ? 3.0D : 2.2D;
                minAngleRad     = Math.toRadians(12.0D);
                maxAccumTicks   = 18;
                headingBlendMin = 0.12D;
                headingBlendMax = 0.32D;
                break;
            case LINE:
                // LINE rotation is dramatic (all workers move sideways) so needs clear direction.
                minAccDist      = workerLeader ? 3.0D : 1.5D;
                minAngleRad     = Math.toRadians(10.0D);
                maxAccumTicks   = 10;
                headingBlendMin = 0.20D;
                headingBlendMax = 0.60D;
                break;
            case WEDGE:
                // Small group — respond more quickly to leader turns
                minAccDist      = workerLeader ? 2.5D : 1.2D;
                minAngleRad     = Math.toRadians(8.0D);
                maxAccumTicks   = 8;
                headingBlendMin = 0.22D;
                headingBlendMax = 0.62D;
                break;
            case COLUMN: // unreachable as a persisted style (WorkerFormationSettings.clamp migrates
                         // it to LINE), kept only so the switch stays exhaustive-by-intent
            case ADAPTIVE:
            default:
                minAccDist      = workerLeader ? 2.5D : 1.8D;
                minAngleRad     = Math.toRadians(8.0D);
                maxAccumTicks   = 12;
                headingBlendMin = 0.18D;
                headingBlendMax = 0.52D;
                break;
        }
        boolean haveAccumulatedHeading = accDistSq >= (minAccDist * minAccDist);
        boolean forceWindowClose = headingAccumTicks >= maxAccumTicks;

        // Below, only targetDirX/Z (where the heading is headed) is ever touched - stableDirX/Z
        // (what everything else actually reads) is slewed toward it a fixed small step per tick
        // at the bottom of this method, regardless of which branch below ran this tick. That's
        // what turns a window's heading decision into a smooth multi-tick turn instead of the
        // whole formation snapping sideways the instant a window closes.
        if (!haveAccumulatedHeading && !forceWindowClose) {
            slewStableDirTowardTarget(workerLeader);
            return;
        }

        if (!haveAccumulatedHeading) {
            // Window timed out without enough net movement.
            // Apply a tiny continuous "drift nudge" toward whatever net direction there was,
            // so the heading slowly tracks the leader even during short, repeated movements.
            if (accDistSq > 0.4D * 0.4D && headingAccumTicks >= 3) {
                double len = Math.sqrt(accDistSq);
                double nx = accDx / len;
                double nz = accDz / len;
                double dot = targetDirX * nx + targetDirZ * nz;
                // Only nudge if we're not going backwards (dot > -0.4)
                if (dot > -0.4D) {
                    double nudge = 0.05D + (dot > 0.6D ? 0.03D : 0.0D); // stronger nudge when already aligned
                    double newDirX = targetDirX + nx * nudge;
                    double newDirZ = targetDirZ + nz * nudge;
                    double norm = Math.sqrt(newDirX * newDirX + newDirZ * newDirZ);
                    if (norm > 1.0E-6D) {
                        targetDirX = newDirX / norm;
                        targetDirZ = newDirZ / norm;
                    }
                }
            }
            headingAnchorX = leader.posX;
            headingAnchorZ = leader.posZ;
            headingAccumTicks = 0;
            slewStableDirTowardTarget(workerLeader);
            return;
        }

        double len = Math.sqrt(accDistSq);
        double nx = accDx / len;
        double nz = accDz / len;

        double currentAngle = Math.atan2(targetDirZ, targetDirX);
        double targetAngle = Math.atan2(nz, nx);
        double delta = FollowMath.wrapToPi(targetAngle - currentAngle);
        double absDelta = Math.abs(delta);

        if (absDelta < minAngleRad) {
            headingAnchorX = leader.posX;
            headingAnchorZ = leader.posZ;
            headingAccumTicks = 0;
            slewStableDirTowardTarget(workerLeader);
            return;
        }

        double dot = targetDirX * nx + targetDirZ * nz;
        if (dot <= HEADING_REVERSE_DOT_LIMIT) {
            double leftDot = targetDirX * (-nz) + targetDirZ * nx;
            double rightDot = targetDirX * nz + targetDirZ * (-nx);
            if (leftDot >= rightDot) {
                targetAngle = currentAngle + (Math.PI * 0.5D);
            } else {
                targetAngle = currentAngle - (Math.PI * 0.5D);
            }
            delta = FollowMath.wrapToPi(targetAngle - currentAngle);
            absDelta = Math.abs(delta);
        }

        double blend = computeHeadingBlend(absDelta, formationSettings, workerLeader, len, headingAccumTicks, headingBlendMin, headingBlendMax);
        double newAngle = currentAngle + delta * blend;

        targetDirX = Math.cos(newAngle);
        targetDirZ = Math.sin(newAngle);

        double sl = Math.sqrt(targetDirX * targetDirX + targetDirZ * targetDirZ);
        if (sl > 1.0E-6D) {
            targetDirX /= sl;
            targetDirZ /= sl;
        } else {
            targetDirX = nx;
            targetDirZ = nz;
        }

        headingAnchorX = leader.posX;
        headingAnchorZ = leader.posZ;
        headingAccumTicks = 0;
        slewStableDirTowardTarget(workerLeader);
    }

    /** Rate-limited per-tick turn of stableDir toward targetDir - see the field comment on
     * targetDirX for why this exists (smooth turning instead of per-window snapping). */
    private void slewStableDirTowardTarget(boolean workerLeader) {
        double maxStepRad = Math.toRadians(workerLeader ? 3.0D : 6.0D);

        double curAngle = Math.atan2(stableDirZ, stableDirX);
        double tgtAngle = Math.atan2(targetDirZ, targetDirX);
        double delta = FollowMath.wrapToPi(tgtAngle - curAngle);
        double step = MathHelper.clamp(delta, -maxStepRad, maxStepRad);
        double newAngle = curAngle + step;

        stableDirX = Math.cos(newAngle);
        stableDirZ = Math.sin(newAngle);
    }

    private double computeHeadingBlend(double absDelta,
                                       WorkerFormationSettings formationSettings,
                                       boolean workerLeader,
                                       double accLen,
                                       int accTicks,
                                       double blendMin,
                                       double blendMax) {
        double smoothBase = formationSettings.getTurnSmoothingFactor();
        double range = blendMax - blendMin;

        double speed = accLen / Math.max(1.0D, accTicks);
        double speedFactor = MathHelper.clamp(speed * (workerLeader ? 5.2D : 4.4D), 0.0D, 1.0D);
        double angleFactor = MathHelper.clamp(absDelta / Math.toRadians(55.0D), 0.0D, 1.0D);

        double blend = blendMin
                + (smoothBase * range * 0.72D)
                + (speedFactor * range * 0.18D)
                + (angleFactor * range * 0.10D);

        return MathHelper.clamp(blend, blendMin, blendMax);
    }
}
