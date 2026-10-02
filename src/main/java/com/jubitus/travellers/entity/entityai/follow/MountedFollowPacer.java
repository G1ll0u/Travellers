package com.jubitus.travellers.entity.entityai.follow;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.pathing.PathBody;
import com.jubitus.travellers.pathing.TravellerNavigateGround;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import javax.annotation.Nullable;

/**
 * Continuous pace-matching for a MOUNTED follower behind a moving leader - the third shared follow
 * helper next to {@link FollowBoatController} and {@link FollowFlightController}, wired into both
 * {@code EntityAIFollowLeader} the same way.
 *
 * <p><b>Why it exists:</b> the normal follow logic is a position servo with hysteresis - settle on
 * the slot, clear the path, wait until the leader has moved past a threshold, then path again. For a
 * pedestrian those thresholds are small enough to read as continuous motion. For a mounted follower
 * they are widened by {@code mountedCatchUpDistanceScale} (which is what stopped the permanent
 * catch-up boost), so the same servo makes a rider stand still until its leader is ~6 blocks ahead
 * and then burst after it - the reported stop-and-go. The two tunings conflict by construction, so
 * this class takes the moving-leader case away from the servo entirely: while the leader is actually
 * moving, steer every tick at a point just ahead of the formation slot, at a speed proportional to
 * the gap. The proportional speed is the whole trick - too close rides slower than the leader, too
 * far rides faster, so the follower converges on riding <em>at the leader's own pace</em> near its
 * slot with no arrive/settle/re-path cycle anywhere. The servo still owns the leader-stopped case
 * (arriving on the slot and standing there), which it is good at.
 *
 * <p>Steering goes through {@link TravellerNavigateGround#steerDirect} - the corridor-proven direct
 * steer (ground resolved, horse body fits, rider headroom checked), never a bare setMoveTo. Terrain
 * the corridor refuses is precisely what the A* planner is for, so a run of refusals (or a
 * physically wedged horse - checked with {@link TravellerMountSupport#isMountWedged}, the only
 * reliable witness for a mounted body) suspends the pacer for a while and hands the tick back to the
 * caller's normal servo/pathfinding, exactly like the follow AIs' own steering-vs-path escalation.
 * Reactive jumps and the wedge sidestep live in {@code TravellerMountSupport.tickMount} and keep
 * working underneath this - the wedge suspend is deliberately slower than they are.
 *
 * <p>Costs nothing per tick beyond the corridor check {@code steerDirect} already throttles
 * internally; no pathfinding, no world scans of its own.
 */
final class MountedFollowPacer {

    /** Leader horizontal speed (blocks/tick) above which it counts as moving. Walking is ~0.2,
     * sneaking ~0.06 - a sneaking leader still paces its escort. */
    private static final double LEADER_MOVING_SPEED = 0.03D;

    /** Consecutive moving ticks before engaging - a single step to adjust position must not yank
     * every mounted escort off its settled slot (same idea as the catchup enter debounce). */
    private static final int ENTER_MOVING_TICKS = 4;

    /** Consecutive still ticks before disengaging. Long enough to ride out a non-player leader's
     * normal travel rhythm - a travel-AI leader pauses for up to a second or two at waypoint
     * changes and stillborn-route detours, and disengaging on those handed every pause to the
     * settle servo and back, which was the stop-and-go with worker/traveller leaders specifically
     * (a walking player never pauses that way). While the leader pauses the follower just stands
     * in its dead zone anyway, so holding the engagement costs nothing. */
    private static final int EXIT_STILL_TICKS = 40;

    /** How far (blocks) ahead of the slot, along the leader's heading, the aim point sits. A fixed
     * distance, not velocity-scaled: at walking speed a velocity-scaled lead lands under two blocks
     * out, and the moment the follower reaches it the bearing to it flips wildly tick to tick - a
     * trace caught the yaw spinning a full turn on the spot right before every suspend. */
    private static final double LEAD_BLOCKS = 3.0D;

    /** Closer than this to the aim point, hold position instead of steering - the bearing to a
     * point almost underfoot is noise, and the moving aim pulls away again within a few ticks. */
    private static final double AIM_DEAD_ZONE = 1.5D;

    /** Extra speed factor per block of gap beyond the aim distance - the proportional half of the
     * controller on top of the leader-matched base. Gentle on purpose: the match term already does
     * the pacing, this only closes drift. */
    private static final double GAP_GAIN = 0.15D;

    /** Slowest the controller ever commands, as a share of base speed - a floor keeps a follower
     * right on its slot still ambling along with the group instead of halting dead. */
    private static final double MIN_PACE_FACTOR = 0.35D;

    /** Fastest the controller ever commands, as a share of base speed - the catch-up cap for a
     * follower that has fallen well behind its slot. */
    private static final double MAX_PACE_FACTOR = 1.8D;

    /** Ground-friction constant of vanilla's steered-horse terminal velocity (see the trace tool's
     * dxz/predicted note in CLAUDE.md: terminal = moveForward x horseAttr / 0.454). Used to invert
     * that physics: from the leader's measured speed to the speed factor that reproduces it. */
    private static final double HORSE_FRICTION = 0.454D;

    /** Wedged ticks in a row before suspending - deliberately slower than tickMount's own reactive
     * jump (immediate) and sidestep (~10 ticks), so those get their chance first. */
    private static final int SUSPEND_WEDGED_TICKS = 24;

    /** How long a suspend lasts. Long enough for the servo to plan and start walking a real route
     * past whatever the corridor refused, short enough to resume fluid riding soon after. */
    private static final int SUSPEND_TICKS = 40;

    /** Beyond this distance to the leader the pacer stands aside - the follow AIs' own far
     * catch-up / stepped-waypoint modes are the right tool there. Base value; each caller widens it
     * by its own existing mount-scale knob (see {@code maxLeaderDistanceScale} in {@link #tick}) - a
     * wide formation ring routinely puts back-rank followers past the base distance as a matter of
     * course, not as a failure, and those followers deserve pacer coverage too. */
    private static final double MAX_LEADER_DISTANCE = 32.0D;

    /** Exponential smoothing factor for the leader's trailing-window speed (blocks/tick), shared by
     * the moving/still classification and the pace-matching factor further down. A mounted leader
     * travelling via EntityAITravel pivots in place and pauses at waypoint changes, so its raw
     * per-tick displacement dips under {@link #LEADER_MOVING_SPEED} constantly even while making
     * steady overall progress - classifying on the instantaneous value disengaged the pacer
     * mid-journey, handing the tick to the servo, which re-paths (exactly the cost this class exists
     * to avoid). Same decay this field already used for pace-matching before this change - only where
     * it gets updated moved, not the constant itself. */
    private static final double LEADER_SPEED_SMOOTHING = 0.9D;

    private final EntityTraveller follower;

    private int leaderMovingTicks;
    private int leaderStillTicks;
    private boolean engaged;
    private int wedgedTicks;
    private int suspendedUntilTick;

    /** Smoothed leader speed (blocks/tick) - per-tick displacement is noisy (jumps, collisions,
     * the leader's own turn slowdowns), and the match term below must not chase that noise. */
    private double smoothedLeaderSpeed;

    /** Smoothed leader velocity components - a trace caught the raw per-tick vector's *direction*
     * (not just its magnitude) swinging wildly while a leader stood nearly still: any sub-block
     * jostle (terrain conforming, a tiny turn) has a near-zero magnitude but an essentially random
     * direction, and normalizing that noisy near-zero vector for the lead offset below amplified it
     * into a full {@link #LEAD_BLOCKS}-sized swing of the aim point every tick - the followers'
     * reported "does whatever, spins in place, colleagues too" was this, not a scheduling/terrain
     * problem. Smoothed the same way {@link #smoothedLeaderSpeed} already is. */
    private double smoothedLeaderVelX;
    private double smoothedLeaderVelZ;

    MountedFollowPacer(EntityTraveller follower) {
        this.follower = follower;
    }

    /** Whether the pacer is currently driving. The owner-follow task's own keep-running check is
     * "far away, or still has a path" - a pacing follower has neither (it steers pathless and rides
     * right at its slot), so the task must stay alive on this instead. */
    boolean isEngaged() {
        return engaged;
    }

    void reset() {
        engaged = false;
        leaderMovingTicks = 0;
        leaderStillTicks = 0;
        wedgedTicks = 0;
        suspendedUntilTick = 0;
        smoothedLeaderSpeed = 0.0D;
        smoothedLeaderVelX = 0.0D;
        smoothedLeaderVelZ = 0.0D;
    }

    /**
     * Steers the follower for this tick if the pacing conditions hold. Returns {@code true} when it
     * handled movement (the caller must skip its own servo/pathing logic for the tick, like it does
     * for the boat and flight controllers); {@code false} hands the tick to the caller unchanged.
     *
     * @param leaderX/Z    the leader's current position (the fallback steering lane, see below)
     * @param leaderVelX/Z the leader's per-tick displacement (pos - prevPos)
     * @param slotX/Z      the follower's fresh ideal formation slot (recomputed by the caller every
     *                     tick - never a cached goal, staleness is the servo problem this bypasses)
     * @param distToLeader horizontal distance to the leader itself
     * @param baseSpeed    the caller's base follow speed multiplier
     * @param maxLeaderDistanceScale widens {@link #MAX_LEADER_DISTANCE} for this caller's own
     *                     mount-scale knob - a wide formation ring routinely puts back-rank
     *                     followers past the base distance as a matter of course, not as a failure.
     * @param leaderEntity the leader itself, only used as a last-resort steering fallback (its own
     *                     recorded trail) when both the aim point and the leader's raw position
     *                     refuse - may be {@code null} (a virtual leader has no live entity to trail).
     */
    boolean tick(double leaderX, double leaderZ, double leaderVelX, double leaderVelZ,
                 double slotX, double slotZ, double distToLeader, double baseSpeed,
                 double maxLeaderDistanceScale, @Nullable EntityLivingBase leaderEntity) {
        if (!TravellersConfig.MOUNTS.mountedPaceFollowEnabled
                || !PathBody.isMounted(follower)
                || !(follower.getNavigator() instanceof TravellerNavigateGround)
                || follower.ticksExisted < suspendedUntilTick
                || distToLeader > MAX_LEADER_DISTANCE * maxLeaderDistanceScale) {
            engaged = false;
            leaderMovingTicks = 0;
            return false;
        }

        double leaderSpeedSq = leaderVelX * leaderVelX + leaderVelZ * leaderVelZ;
        double instLeaderSpeed = Math.sqrt(leaderSpeedSq);
        // Updated unconditionally, even before engaging, so the classification below is never
        // chasing a value that only started moving the instant it was read - see LEADER_SPEED_SMOOTHING.
        smoothedLeaderSpeed = smoothedLeaderSpeed * LEADER_SPEED_SMOOTHING
                + instLeaderSpeed * (1.0D - LEADER_SPEED_SMOOTHING);
        smoothedLeaderVelX = smoothedLeaderVelX * LEADER_SPEED_SMOOTHING
                + leaderVelX * (1.0D - LEADER_SPEED_SMOOTHING);
        smoothedLeaderVelZ = smoothedLeaderVelZ * LEADER_SPEED_SMOOTHING
                + leaderVelZ * (1.0D - LEADER_SPEED_SMOOTHING);

        if (smoothedLeaderSpeed > LEADER_MOVING_SPEED) {
            leaderMovingTicks++;
            leaderStillTicks = 0;
        } else {
            leaderStillTicks++;
            if (leaderStillTicks > EXIT_STILL_TICKS) {
                engaged = false;
                leaderMovingTicks = 0;
                return false;
            }
        }

        if (!engaged) {
            if (leaderMovingTicks < ENTER_MOVING_TICKS) return false;
            engaged = true;
            wedgedTicks = 0;
        }

        if (TravellerMountSupport.isMountWedged(follower)) {
            if (++wedgedTicks >= SUSPEND_WEDGED_TICKS) {
                suspend();
                return false;
            }
        } else if (wedgedTicks > 0) {
            wedgedTicks--;
        }

        double aimX = slotX;
        double aimZ = slotZ;
        // Smoothed, not raw: the raw per-tick vector's direction is meaningless noise below
        // LEADER_MOVING_SPEED (see the field doc on smoothedLeaderVelX/Z) - same threshold the
        // engage/disengage decision above already uses, so "moving enough to aim ahead" and "moving
        // enough to stay engaged" never disagree.
        if (smoothedLeaderSpeed > LEADER_MOVING_SPEED) {
            aimX += (smoothedLeaderVelX / smoothedLeaderSpeed) * LEAD_BLOCKS;
            aimZ += (smoothedLeaderVelZ / smoothedLeaderSpeed) * LEAD_BLOCKS;
        }
        double dx = aimX - follower.posX;
        double dz = aimZ - follower.posZ;
        double gap = Math.sqrt(dx * dx + dz * dz);

        // Past the aim (or the leader is mid-pause and the aim collapsed onto the slot underfoot):
        // stand and let the moving aim pull ahead again. The move helper holds no order, so the
        // horse simply coasts to a stop in formation.
        if (gap < AIM_DEAD_ZONE) return true;

        // Adapting to the leader's speed, literally: invert the steered-horse ground physics
        // (terminal = AIMoveSpeed x horseAttr / friction, with horseAttr mirrored from AIMoveSpeed
        // times the follow multiplier - see applyMountedSpeed) to get the exact speed factor at
        // which this follower's horse moves at the leader's measured pace, whoever the leader is
        // and however fast it goes. The gap term on top is only drift correction. A distance-only
        // ramp had no idea how fast the leader actually was: its equilibrium sat wherever the gap
        // happened to balance it, chronically behind a fast non-player leader.
        double riderAttr = follower.getEntityAttribute(
                net.minecraft.entity.SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue();
        double followMult = TravellersConfig.MOUNTS.mountedFollowSpeedMultiplier;
        double matchFactor = 0.0D;
        if (riderAttr > 1.0E-4D && baseSpeed > 1.0E-4D) {
            double needAIMove = Math.sqrt(HORSE_FRICTION * smoothedLeaderSpeed / followMult);
            matchFactor = needAIMove / (baseSpeed * riderAttr);
        }

        double factor = MathHelper.clamp(
                matchFactor + GAP_GAIN * (gap - LEAD_BLOCKS),
                MIN_PACE_FACTOR, MAX_PACE_FACTOR);

        TravellerNavigateGround nav = (TravellerNavigateGround) follower.getNavigator();
        // Same leg-at-a-time proving EntityAITravel's own direct-ride uses (mountedDirectLegBlocks) -
        // aimX/aimZ can sit well beyond one leg's length for a follower that has fallen behind its
        // slot, and steerDirect's corridor test covers the whole line in one shot: on rolling terrain
        // that is far more likely to hit the rise/drop budget or a mid-line obstacle than a short
        // stretch would, which is what made this pacer suspend (and hand off to the servo/A*) far
        // more often on hills than the leader itself ever needs to.
        double legBlocks = TravellersConfig.MOUNTS.mountedDirectLegBlocks;
        boolean steered = nav.steerDirectLegCapped(aimX, aimZ, legBlocks, baseSpeed * factor);
        if (!steered && distToLeader > LEAD_BLOCKS) {
            // The straight line to the slot can clip a trunk the leader simply walked past - in a
            // forest the one lane proven open is the leader's own. Fall in directly behind them
            // before giving up on the tick.
            steered = nav.steerDirectLegCapped(leaderX, leaderZ, legBlocks, baseSpeed * factor);
        }
        if (!steered && leaderEntity != null) {
            // Both the aim point and the leader's raw position refused - rolling terrain, not a
            // one-off obstacle. Retrace the leader's own recent trail instead of guessing a third
            // blind line: it already proved this ground passable a few ticks ago. Same primitive a
            // led horse uses to follow its rider's footsteps - a traveller/raider/worker leader
            // carries its own (EntityTraveller.leadTrailPointBehind); a player leader has one via
            // PlayerLeaderTrail instead, recorded by EntityAIFollowLeader each tick.
            Vec3d trailPoint = leaderEntity instanceof EntityTraveller
                    ? ((EntityTraveller) leaderEntity).leadTrailPointBehind(distToLeader)
                    : PlayerLeaderTrail.pointBehind(leaderEntity.getUniqueID(), distToLeader);
            if (trailPoint != null) {
                steered = nav.steerDirectLegCapped(trailPoint.x, trailPoint.z, legBlocks, baseSpeed * factor);
            }
        }
        if (!steered) {
            // Corridor refusals are terrain, and terrain doesn't change while standing still -
            // retrying in place would just freeze the follower. Hand the very same tick back to the
            // caller's servo (which can plan a real route) and stand aside for a moment.
            suspend();
            return false;
        }
        // The steer owns movement now - an A* path left running would keep writing its own
        // steering over ours from the entity tick. Cleared on success only: a refused steer must
        // not throw away the route the servo may be mid-walking.
        if (!follower.getNavigator().noPath()) follower.getNavigator().clearPath();
        return true;
    }

    private void suspend() {
        engaged = false;
        leaderMovingTicks = 0;
        suspendedUntilTick = follower.ticksExisted + SUSPEND_TICKS;
        com.jubitus.travellers.debug.trace.TravellerTracer.event(follower, "follow",
                "pace-follow suspended (wedged=" + wedgedTicks
                        + " ticks) - normal follow pathing takes over");
    }
}
