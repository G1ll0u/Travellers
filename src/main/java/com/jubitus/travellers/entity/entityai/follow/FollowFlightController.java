package com.jubitus.travellers.entity.entityai.follow;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.entityai.flight.ElytraCapability;
import com.jubitus.travellers.entity.entityai.flight.ElytraRocketBoost;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import javax.annotation.Nullable;

/**
 * Shared "my leader just took off" logic: a follower wearing an elytra launches with whoever it is
 * following and chases them through the air. Used by both worker escort following
 * ({@link EntityAIFollowLeader}) and traveller/raider group following
 * ({@link EntityAIFollowLeader}) - extracted for the same reason {@link FollowBoatController}
 * was, so the two can never drift apart.
 *
 * <p>No opportunity scan is involved here, unlike the travel AI's own takeoffs: the reason to fly is
 * simply that the target is flying. A follower cannot climb without fireworks, so one that gets left
 * behind lands and reverts to the ordinary follow/catchup behaviour - that is the expected outcome,
 * not a failure.
 *
 * <p>The moment the target leaves the ground is treated specially ({@link #groupJoinTicksRemaining}):
 * for a short window afterwards every eligible follower launches on its own short stagger instead of
 * waiting until it has fallen far enough behind to look "worth chasing". That is what keeps a whole
 * escort leaving the ground together - and launching from wherever it already stood, rather than only
 * once it has drifted far from the leader's takeoff spot - which is also what keeps a group launching
 * off uneven terrain (a slope, a cliff edge) from ending up scattered at wildly different altitudes.
 */
final class FollowFlightController {

    /** Ticks between takeoff attempts while the target stays airborne. */
    private static final int RETRY_DELAY_TICKS = 20;
    /** The target must be at least this far above the follower, or this far away, to be worth chasing by air. */
    private static final double MIN_CHASE_HEIGHT = 3.0D;
    private static final double MIN_CHASE_DISTANCE = 12.0D;
    /** How far above the follower the target must be before a rocket is worth spending. */
    private static final double ROCKET_HEIGHT_DEFICIT = 8.0D;

    private final EntityTraveller follower;
    /**
     * This follower's fixed angle (radians) on the ring formation around the target - stable per
     * entity, deliberately NOT derived from the target's current heading. See {@link #chasePoint}.
     */
    private final double ringAngleRad;

    private int activationDelay;
    private int retryDelay;
    private int boostCooldown;
    private int rocketsUsedThisFlight;
    private boolean targetWasFlying;
    /** Counts down from {@code followTakeoffJoinWindowTicks} after the target's takeoff edge; see class doc. */
    private int groupJoinTicksRemaining;
    /** Consecutive ticks the vertical/horizontal gap has looked rocket-worthy; see {@link #tickRocketBoost}. */
    private int verticalNeedTicks;
    private int horizontalNeedTicks;

    FollowFlightController(EntityTraveller follower) {
        this.follower = follower;
        this.ringAngleRad = (Math.abs(follower.getEntityId()) % 360) * (Math.PI / 180.0D);
    }

    void reset() {
        activationDelay = 0;
        retryDelay = 0;
        boostCooldown = 0;
        rocketsUsedThisFlight = 0;
        targetWasFlying = false;
        groupJoinTicksRemaining = 0;
        verticalNeedTicks = 0;
        horizontalNeedTicks = 0;
    }

    /**
     * True when this controller owns the follower's movement - either it is already flying, or the
     * target is airborne and it can join them. Keeps the follow task alive across the flight.
     */
    boolean shouldHandle(@Nullable EntityLivingBase target) {
        if (!TravellersConfig.ELYTRA.enabled || !TravellersConfig.ELYTRA.followTargetFlight) return false;
        // An in-progress flight must always be seen through to a landing, target or no target.
        if (follower.elytraFlight.isActive()) return true;
        return target != null && target.isElytraFlying() && ElytraCapability.canTakeOff(follower);
    }

    /** @return true if this tick was handled and the normal follow logic should be skipped. */
    boolean tick(@Nullable EntityLivingBase target) {
        if (!TravellersConfig.ELYTRA.enabled || !TravellersConfig.ELYTRA.followTargetFlight) return false;

        boolean targetFlying = target != null && target.isElytraFlying();

        // Stagger the group's reaction so a whole escort doesn't launch on the exact same tick, but
        // keep it short - opens a join window (below) during which the "worth chasing" gate is
        // skipped entirely, so the group leaves together rather than each follower waiting until it
        // has individually fallen behind enough to bother.
        if (targetFlying && !targetWasFlying) {
            int stagger = Math.max(0, TravellersConfig.ELYTRA.followTakeoffStaggerTicks);
            activationDelay = stagger == 0 ? 0 : Math.abs(follower.getEntityId() * 7) % (stagger + 1);
            groupJoinTicksRemaining = TravellersConfig.ELYTRA.followTakeoffJoinWindowTicks;
        }
        targetWasFlying = targetFlying;
        if (groupJoinTicksRemaining > 0) groupJoinTicksRemaining--;

        if (follower.elytraFlight.isActive()) {
            // Tells the flight controller its target is a moving formation slot, not a fixed landing
            // spot - see ElytraFlightController.setChaseMode for why that changes how it dives/climbs.
            follower.elytraFlight.setChaseMode(true);
            if (targetFlying) {
                if (horizontalDistanceTo(target) > TravellersConfig.ELYTRA.followFlightAbortDistance) {
                    // Falling this far behind risks drifting out of loaded chunks mid-flight, where a
                    // forced virtualize would yank it out of the air entirely. Land now and let the
                    // ordinary ground/catchup path - which already has its own teleport rescue for a
                    // gap this big - close it instead of trailing further behind in the air.
                    follower.elytraFlight.abortToLanding();
                } else {
                    follower.elytraFlight.updateTarget(chasePoint(target));
                    // Raw (un-projected) distance to the slot, not to the lead-projected chasePoint -
                    // the mirror blend needs "how far out of formation am I", which a lead offset would
                    // otherwise keep permanently non-zero even once actually in position.
                    Vec3d slot = slotPoint(target);
                    double slotDistance = follower.getDistance(slot.x, slot.y, slot.z);
                    follower.elytraFlight.setChaseReference(target.rotationPitch, target.rotationYaw, slotDistance);
                    tickRocketBoost(target);
                }
            } else {
                // They landed, or we lost them - come down too rather than glide off alone.
                follower.elytraFlight.abortToLanding();
            }
            return true;
        }

        if (!targetFlying) {
            retryDelay = 0;
            rocketsUsedThisFlight = 0;
            return false;
        }

        if (activationDelay > 0) {
            activationDelay--;
            return false;
        }
        if (retryDelay > 0) {
            retryDelay--;
            return false;
        }

        // Inside the join window every eligible follower launches unconditionally; outside it (a
        // straggler rejoining a flight already well underway) the ordinary distance/height gate applies.
        if (groupJoinTicksRemaining <= 0 && !isWorthChasing(target)) return false;
        if (!ElytraCapability.canTakeOff(follower)) return false;

        retryDelay = RETRY_DELAY_TICKS;
        follower.elytraFlight.setChaseMode(true);
        return follower.elytraFlight.requestTakeoff(chasePoint(target), true);
    }

    /**
     * Spend a rocket only when gliding genuinely cannot solve the problem: the target is pulling away,
     * vertically or horizontally, and losing ground while still able to close it by gliding alone is
     * what a glide is for.
     *
     * <p>Two guards on top of the raw distance check keep this from turning into a wasteful back-and-forth
     * - a rocket overshooting past the target and needing a second one right back the other way to
     * correct, or a brief height/speed wobble from the player triggering one on its own:
     * <ul>
     * <li>{@code followRocketConfirmTicks} - the gap has to hold for a short run of consecutive ticks
     * before it counts, so a momentary wobble never fires anything on its own.</li>
     * <li>{@link #isAlreadyClosingGap} - skip firing if the follower is already closing the gap fast
     * enough under its own power (still gliding off a previous boost, or just diving well). Stacking a
     * rocket on top of an already-effective approach is exactly what overshoots past the target.</li>
     * </ul>
     */
    private void tickRocketBoost(EntityLivingBase target) {
        if (!TravellersConfig.ELYTRA.workerFireworkBoost) return;
        if (!(follower instanceof EntityTravellerWorker)) return;

        EntityTravellerWorker worker = (EntityTravellerWorker) follower;
        if (!worker.isHiredFollower()) return;

        TravellersConfig.Elytra cfg = TravellersConfig.ELYTRA;

        double verticalDeficit = target.posY - follower.posY;
        boolean verticalGap = verticalDeficit >= ROCKET_HEIGHT_DEFICIT;
        // A player accelerating away in level flight (not climbing) never trips the vertical check
        // above at all - this is what makes a rocket worth spending to catch up sideways too.
        boolean horizontalGap = horizontalDistanceTo(target) >= cfg.followRocketHorizontalDeficit;

        // Point steeply at the target for as long as the raw vertical gap exists - not gated behind the
        // confirm window below, and not only in the window right after a rocket actually ignites.
        // Vanilla's boost pulls the entity toward its own look vector and locks that motion in within
        // the firework's first couple of ticks, so if the pitch is still ramping up from a shallow
        // glide angle when it ignites, most of the boost is spent going sideways instead of up. This
        // costs nothing to keep renewed, so it gets the aim ready well before firing is even considered.
        if (verticalGap) {
            follower.elytraFlight.requestSteepClimb(cfg.followRocketClimbHoldTicks);
        }

        verticalNeedTicks = verticalGap ? verticalNeedTicks + 1 : 0;
        horizontalNeedTicks = horizontalGap ? horizontalNeedTicks + 1 : 0;
        boolean confirmed = verticalNeedTicks >= cfg.followRocketConfirmTicks
                || horizontalNeedTicks >= cfg.followRocketConfirmTicks;

        if (boostCooldown > 0) {
            boostCooldown--;
            return;
        }
        if (rocketsUsedThisFlight >= cfg.maxFireworksPerFlight) return;
        if (!confirmed) return;
        if (isAlreadyClosingGap(target, cfg)) return;
        if (!ElytraRocketBoost.hasRocket(worker)) return;

        if (ElytraRocketBoost.tryBoost(worker)) {
            rocketsUsedThisFlight++;
            boostCooldown = cfg.fireworkBoostIntervalTicks;
            verticalNeedTicks = 0;
            horizontalNeedTicks = 0;
        }
    }

    /**
     * Is the follower's current motion already carrying it toward the target fast enough on its own?
     * A single dot product covers vertical and horizontal at once - whichever gap actually triggered
     * the check, closing speed is closing speed.
     */
    private boolean isAlreadyClosingGap(EntityLivingBase target, TravellersConfig.Elytra cfg) {
        double dx = target.posX - follower.posX;
        double dy = target.posY - follower.posY;
        double dz = target.posZ - follower.posZ;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 1.0E-4D) return true;

        double closingSpeed = (follower.motionX * dx + follower.motionY * dy + follower.motionZ * dz) / dist;
        return closingSpeed >= cfg.followRocketSkipClosingSpeed;
    }

    private double horizontalDistanceTo(EntityLivingBase target) {
        double dx = target.posX - follower.posX;
        double dz = target.posZ - follower.posZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Don't leap after someone skimming along at head height a few blocks away - the follower would
     * hop, glide briefly and land, over and over, for no gain.
     */
    private boolean isWorthChasing(EntityLivingBase target) {
        if (target.posY - follower.posY >= MIN_CHASE_HEIGHT) return true;
        return horizontalDistanceTo(target) >= MIN_CHASE_DISTANCE;
    }

    /**
     * Where the target is going, not where it is: at glide speeds, aiming at the current position
     * means permanently trailing behind by whatever the last reaction cost.
     *
     * <p>The vertical lead is deliberately shorter than the horizontal one, and clamped outright
     * ({@code followChaseMaxAltitudeLead}): a player boosting straight up with fireworks briefly hits a
     * climb rate far higher than steady flight, and extrapolating that as far ahead as the horizontal
     * lead does would aim followers at a wildly overshot altitude.
     *
     * <p>Each follower's sideways offset sits at a fixed angle on a ring around the target
     * ({@link #ringAngleRad}), not fanned relative to the target's current heading. A heading-relative
     * fan collapses to nothing the moment the target has no horizontal motion at all - exactly a
     * player boosting straight up with fireworks - which piles every follower's aim point onto the
     * same single spot: they jostle for it, and with the horizontal distance near zero the desired yaw
     * flips wildly tick to tick from ordinary position noise, which is what reads as them "orbiting"
     * instead of climbing straight up. A fixed ring keeps a real minimum radius (and so a stable aim
     * direction) no matter which way the target is currently moving.
     */
    private Vec3d chasePoint(EntityLivingBase target) {
        TravellersConfig.Elytra cfg = TravellersConfig.ELYTRA;

        double x = target.posX + target.motionX * cfg.followChaseLeadTicks;
        double z = target.posZ + target.motionZ * cfg.followChaseLeadTicks;

        double verticalLead = MathHelper.clamp(
                target.motionY * cfg.followChaseVerticalLeadTicks,
                -cfg.followChaseMaxAltitudeLead, cfg.followChaseMaxAltitudeLead);
        double y = target.posY + verticalLead;

        Vec3d ring = ringOffset(cfg);
        return new Vec3d(x + ring.x, y + ring.y, z + ring.z);
    }

    /**
     * The formation slot itself, with no lead projection - where the follower actually belongs right
     * now, as opposed to {@link #chasePoint} which aims a little ahead of it. Used only to measure how
     * far out of formation a follower currently is (see {@link ElytraFlightController#setChaseReference}):
     * measuring against the lead-projected point instead would never read as "arrived" even once a
     * follower is genuinely flying alongside its leader, since that point is permanently out ahead.
     */
    private Vec3d slotPoint(EntityLivingBase target) {
        Vec3d ring = ringOffset(TravellersConfig.ELYTRA);
        return new Vec3d(target.posX + ring.x, target.posY + ring.y, target.posZ + ring.z);
    }

    private Vec3d ringOffset(TravellersConfig.Elytra cfg) {
        double x = Math.cos(ringAngleRad) * cfg.followFormationLateralSpacing;
        double z = Math.sin(ringAngleRad) * cfg.followFormationLateralSpacing;
        double y = ((follower.getEntityId() / 5 % 3) - 1) * cfg.followFormationVerticalSpacing;
        return new Vec3d(x, y, z);
    }
}
