package com.jubitus.travellers.entity.entityai.flight;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;

import javax.annotation.Nullable;

/**
 * Drives one entity's elytra flight, from takeoff to landing. Deliberately knows nothing about
 * <em>why</em> it is flying - the travel AI, the follow AIs and the debug command all just hand it
 * a target and let it fly.
 *
 * <p>The physics itself is untouched vanilla ({@link net.minecraft.entity.EntityLivingBase#travel},
 * which has no player-only gate): this class only writes {@code rotationPitch}/{@code rotationYaw}
 * and lets vanilla turn that into motion. That is why it must be ticked from
 * {@link EntityTraveller#travel} rather than from {@code onLivingUpdate} - vanilla runs the AI (and
 * with it the look helper, which would overwrite our rotations) at
 * {@code EntityLivingBase:2582} and only then calls {@code travel()} at {@code :2616}. Steering from
 * inside {@code travel()} is what makes our heading win, in the same tick the physics consumes it.
 */
public final class ElytraFlightController {

    public enum State { IDLE, ALIGNING, LAUNCHING, FLYING }

    /** How long a launch may stay stuck on the ground before it is written off. */
    private static final int LAUNCH_TIMEOUT_TICKS = 12;
    /** How close to facing the destination counts as aligned, in degrees. */
    private static final float ALIGN_TOLERANCE_DEGREES = 6.0F;
    /** Give up on turning to face the destination after this long - something is fighting our heading. */
    private static final int ALIGN_TIMEOUT_TICKS = 60;
    /** Cooldown applied after a launch that never got airborne, so it isn't retried every tick. */
    private static final int FAILED_LAUNCH_COOLDOWN = 60;
    /** Extra ticks a flight gets to bring itself down before it is simply ended. */
    private static final int FORCED_LANDING_GRACE_TICKS = 200;
    /** Sideways probe angle used to pick a way around an obstacle, in degrees. */
    private static final float AVOID_PROBE_ANGLE = 35.0F;
    /** How fast the obstacle-avoidance biases decay once the way is clear again. */
    private static final float AVOID_DECAY = 0.85F;

    private final EntityTraveller mob;

    private State state = State.IDLE;
    private Vec3d target;
    private int alignTicks;
    private int launchTicks;
    private int flightTicks;
    private int cooldownTicks;
    private int lookAheadTimer;
    private float avoidYawBias;
    private float avoidPitchBias;
    private boolean forceLanding;
    /** Set by {@link #computePitch}: nose-up, settling toward a landing. */
    private boolean flaring;
    /** Ticks remaining on a {@link #requestSteepClimb} override. */
    private int steepClimbTicks;
    /** Set by a follow controller while it owns this flight; see {@link #setChaseMode}. */
    private boolean chaseMode;
    /** See {@link #setChaseReference}. */
    private float chaseLeaderPitch;
    private float chaseLeaderYaw;
    private double chaseSlotDistance = Double.MAX_VALUE;
    /** See {@link #isPendingSafeDescent}. */
    private boolean pendingSafeDescent;

    public ElytraFlightController(EntityTraveller mob) {
        this.mob = mob;
    }

    // ---- public API ----

    public State getState() {
        return state;
    }

    /** Airborne under elytra control, or on the way there. */
    public boolean isActive() {
        return state != State.IDLE;
    }

    public boolean isFlying() {
        return state == State.FLYING;
    }

    @Nullable
    public Vec3d getTarget() {
        return target;
    }

    public boolean isOnCooldown() {
        return cooldownTicks > 0;
    }

    /**
     * Start a flight toward {@code destination}. Returns false when the entity may not fly, is
     * already flying, or is still on its post-landing cooldown.
     */
    public boolean requestTakeoff(Vec3d destination) {
        return requestTakeoff(destination, false);
    }

    /**
     * @param ignoreCooldown skip the post-landing cooldown. Only for following someone who is already
     *                       in the air: there the cooldown's job (not spamming takeoffs) is served by
     *                       the follow controller's own retry delay, and honouring it would strand a
     *                       follower on the ground watching their leader fly away.
     */
    public boolean requestTakeoff(Vec3d destination, boolean ignoreCooldown) {
        if (destination == null) return false;
        if (state != State.IDLE) return false;
        if (cooldownTicks > 0 && !ignoreCooldown) return false;
        if (!ElytraCapability.canTakeOff(mob)) return false;

        this.target = destination;
        // Turn to face the destination first and jump from a standstill. Leaping first and turning in
        // the air wastes the opening half of the glide - the part with the most height to spend - on
        // a correction turn, and that is what makes a flight land wide of where it was aimed.
        this.state = mob.onGround ? State.ALIGNING : State.LAUNCHING;
        this.alignTicks = 0;
        this.launchTicks = 0;
        this.flightTicks = 0;
        this.forceLanding = false;
        this.flaring = false;
        this.avoidYawBias = 0.0F;
        this.avoidPitchBias = 0.0F;
        this.lookAheadTimer = 0;

        mob.getNavigator().clearPath();

        debug("takeoff requested toward %.1f/%.1f/%.1f", destination.x, destination.y, destination.z);
        return true;
    }

    /** Retarget an in-progress flight, e.g. a follower whose leader moved. */
    public void updateTarget(Vec3d destination) {
        if (state != State.IDLE && destination != null && !forceLanding) {
            this.target = destination;
        }
    }

    /** Bring the flight down at the first opportunity instead of continuing to the target. */
    public void abortToLanding() {
        if (state != State.IDLE) forceLanding = true;
    }

    /**
     * Re-arms a flight that was just cut off mid-air by something other than a real landing - so far,
     * only {@code EntityTraveller.forceVirtualizeResolvingBlockers()}, which has to clear the flight
     * before knowing whether virtualizing will actually succeed, and must not leave the entity mid-air
     * with the elytra flag cleared and nobody steering if it turns out not to. Resumes as FLYING with
     * an immediate forced landing rather than trying to recover the original destination - the point is
     * just "come down under control", not "get back on route". A no-op if there is nothing left to fly
     * with (elytra gone, dead, etc.) - there is no controlled option left at that point.
     */
    public void emergencyRecover() {
        if (!ElytraCapability.canKeepFlying(mob)) return;
        state = State.FLYING;
        flightTicks = 0;
        target = null;
        forceLanding = true;
        chaseMode = false;
        flaring = false;
        avoidYawBias = 0.0F;
        avoidPitchBias = 0.0F;
        mob.setElytraFlying(true);
        debug("emergency-recovered mid-air after an aborted virtualize");
    }

    /**
     * Override the normal unpowered-glide climb limit for a short while: point steeply at the target
     * instead of the shallow {@code maxClimbPitch} angle. Meant for a follower that just lit a firework
     * to catch up vertically - a rocket compensates for the stall a steep climb would otherwise cause,
     * so aiming shallow under boost only wastes most of the impulse on horizontal motion (the boost
     * pulls the entity toward its own look vector). Renew every tick it should still apply; the
     * override decays on its own once nobody asks for it again.
     */
    public void requestSteepClimb(int ticks) {
        steepClimbTicks = Math.max(steepClimbTicks, ticks);
    }

    /**
     * Whether a follow controller is currently steering this flight toward a moving formation slot,
     * as opposed to the travel AI gliding toward a fixed landing spot. Changes how {@link #computePitch}
     * reads a short horizontal distance to the target: for a point-to-point glide that means "arriving,
     * flare for a soft landing"; for a follower it is simply the ring formation radius and true nearly
     * all the time, so treating it as an approach would pin the nose near level - unable to climb OR
     * dive - regardless of how far above or below its target it actually is. The follow controller sets
     * this every tick it is active and never needs to clear it - {@link #land()} always does, and no
     * other flight (travel AI, the debug command) ever sets it.
     */
    public void setChaseMode(boolean active) {
        this.chaseMode = active;
    }

    /**
     * The leader's own current attitude, and how far the follower actually sits from its formation
     * slot right now (not the lead-projected aim point in {@link #target} - the raw, un-projected
     * distance). {@link #steer()}/{@link #computePitch} blend toward flying with this exact pitch/yaw
     * as that distance shrinks, instead of always steering hard at the aim point.
     *
     * <p>This is what makes formation flight read as natural rather than as a swarm of missiles locked
     * onto a moving point: once a follower is actually near its slot, matching the leader's own nose
     * angle keeps it moving at the same rate the leader is (no more, no less) - it neither overshoots
     * past the leader nor needs a firework to correct back after it does. Only while genuinely far from
     * the slot does the aim-point-chasing correction (climb/dive angle, turn toward the slot) dominate.
     */
    public void setChaseReference(float leaderPitch, float leaderYaw, double slotDistance) {
        this.chaseLeaderPitch = leaderPitch;
        this.chaseLeaderYaw = leaderYaw;
        this.chaseSlotDistance = slotDistance;
    }

    /** Hard reset with no cooldown - for despawn, virtualize and materialize. */
    public void reset() {
        state = State.IDLE;
        target = null;
        alignTicks = 0;
        launchTicks = 0;
        flightTicks = 0;
        cooldownTicks = 0;
        avoidYawBias = 0.0F;
        avoidPitchBias = 0.0F;
        forceLanding = false;
        flaring = false;
        steepClimbTicks = 0;
        chaseMode = false;
        chaseSlotDistance = Double.MAX_VALUE;
        pendingSafeDescent = false;
    }

    /** Ticked every tick from {@link EntityTraveller#travel}, server side only. */
    public void tick() {
        if (cooldownTicks > 0) cooldownTicks--;
        if (steepClimbTicks > 0) steepClimbTicks--;

        switch (state) {
            case ALIGNING:
                tickAlign();
                break;
            case LAUNCHING:
                tickLaunch();
                break;
            case FLYING:
                tickFlight();
                break;
            default:
                break;
        }
    }

    // ---- align ----

    /**
     * Stand still, turn to face the destination, then jump. The entity's own movement is zeroed for
     * these few ticks so it pivots on the spot instead of drifting off the edge it is about to launch
     * from - which matters most in exactly the situation this feature is for, a cliff lip.
     */
    private void tickAlign() {
        alignTicks++;

        if (!ElytraCapability.canTakeOff(mob)) {
            failLaunch("state changed while lining up");
            return;
        }
        if (target == null) {
            failLaunch("lost target while lining up");
            return;
        }
        if (!mob.onGround) {
            // Stepped off an edge mid-turn - already airborne, so go straight to flying.
            state = State.LAUNCHING;
            return;
        }

        mob.getNavigator().clearPath();
        mob.moveForward = 0.0F;
        mob.moveStrafing = 0.0F;
        // Bleed off whatever they were carrying when they decided to fly, so the jump starts from a
        // standstill rather than from a run in the old direction.
        mob.motionX *= 0.6D;
        mob.motionZ *= 0.6D;

        double dx = target.x - mob.posX;
        double dz = target.z - mob.posZ;
        float desiredYaw = (float) (MathHelper.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;

        float yaw = approachDegrees(mob.rotationYaw, desiredYaw, (float) TravellersConfig.ELYTRA.alignTurnPerTick);
        mob.rotationYaw = yaw;
        mob.rotationYawHead = yaw;
        mob.renderYawOffset = yaw;
        mob.rotationPitch = 0.0F;

        if (Math.abs(MathHelper.wrapDegrees(desiredYaw - yaw)) <= ALIGN_TOLERANCE_DEGREES) {
            if (TravellersConfig.ELYTRA.assistedLaunch) {
                applyLaunchImpulse(target);
            }
            state = State.LAUNCHING;
            debug("aligned after %d ticks, jumping", alignTicks);
            return;
        }

        if (alignTicks > ALIGN_TIMEOUT_TICKS) {
            failLaunch("could not turn to face the destination");
        }
    }

    // ---- launch ----

    private void applyLaunchImpulse(Vec3d destination) {
        TravellersConfig.Elytra cfg = TravellersConfig.ELYTRA;

        double dx = destination.x - mob.posX;
        double dz = destination.z - mob.posZ;
        double dh = Math.sqrt(dx * dx + dz * dz);

        mob.motionY = cfg.launchVerticalBoost;
        if (dh > 1.0E-4D) {
            mob.motionX += dx / dh * cfg.launchForwardBoost;
            mob.motionZ += dz / dh * cfg.launchForwardBoost;
        }
        mob.velocityChanged = true;
    }

    private void tickLaunch() {
        launchTicks++;

        if (!ElytraCapability.canKeepFlying(mob)) {
            failLaunch("lost elytra during launch");
            return;
        }

        if (!mob.onGround) {
            // Vanilla validates flag 7 in updateElytra() one line before travel() runs, so setting it
            // here takes effect on this very tick.
            mob.setElytraFlying(true);
            state = State.FLYING;
            flightTicks = 0;
            debug("airborne");
            // Steer on this very tick: vanilla consumes the attitude immediately below, and whatever
            // the look helper last left in rotationPitch would otherwise fly the first tick.
            steer();
            return;
        }

        if (launchTicks > LAUNCH_TIMEOUT_TICKS) {
            failLaunch("never left the ground");
        }
    }

    private void failLaunch(String why) {
        debug("launch failed: %s", why);
        state = State.IDLE;
        target = null;
        cooldownTicks = FAILED_LAUNCH_COOLDOWN;
    }

    // ---- flight ----

    private void tickFlight() {
        flightTicks++;
        ElytraCapability.maintainElytra(mob);

        if (mob.onGround) {
            land("touched down");
            return;
        }
        // Hitting water or lava ends the flight just as firmly as hitting ground. Vanilla's elytra
        // branch is gated on being in neither (EntityLivingBase:2048-2050), so once submerged there is
        // no elytra physics left to steer - and updateElytra() only clears its own flag when onGround,
        // which never happens while floating. Both this state machine and vanilla's flag would stay
        // stuck on forever, and with isActive() true the travel and follow AIs keep handing us every
        // tick: the entity simply stops behaving.
        if (mob.isInWater() || mob.isInLava()) {
            land("ditched in liquid");
            return;
        }
        if (!ElytraCapability.canKeepFlying(mob)) {
            // The wings are gone mid-air; there is nothing left to steer with.
            land("elytra no longer usable");
            return;
        }
        if (target == null) {
            forceLanding = true;
        }
        if (flightTicks > TravellersConfig.ELYTRA.maxFlightTicks) {
            forceLanding = true;
        }
        // Last resort for a flight that is somehow never going to touch anything: give the descent a
        // fair chance to work first, then stop flying outright rather than glide forever.
        if (flightTicks > TravellersConfig.ELYTRA.maxFlightTicks + FORCED_LANDING_GRACE_TICKS) {
            land("flight overran its time limit");
            return;
        }
        // A fight that starts mid-air ends the flight, but by landing - never by dropping.
        if (mob.getAttackTarget() != null) {
            forceLanding = true;
        }

        if (!mob.isElytraFlying()) {
            mob.setElytraFlying(true);
        }

        // The navigator has nothing to contribute in the air, and a live path would resume the moment
        // we touch down in the wrong place.
        mob.getNavigator().clearPath();

        steer();

        // Must happen here, not on landing: vanilla applies fall damage inside move(), which runs in
        // super.travel() further down this same tick - by the time we observe onGround it is already
        // dealt. Only a flaring (nose-up, settling) approach is protected, so a nose-down crash into
        // terrain still hurts.
        if (flaring && TravellersConfig.ELYTRA.preventLandingFallDamage) {
            mob.fallDistance = 0.0F;
        }
    }

    private void steer() {
        TravellersConfig.Elytra cfg = TravellersConfig.ELYTRA;

        double dx = target != null ? target.x - mob.posX : mob.getLookVec().x;
        double dz = target != null ? target.z - mob.posZ : mob.getLookVec().z;
        double dh = Math.sqrt(dx * dx + dz * dz);

        if (lookAheadTimer > 0) {
            lookAheadTimer--;
        } else {
            lookAheadTimer = cfg.lookAheadIntervalTicks;
            probeObstacles();
        }

        // In chase mode, blend toward flying with the leader's own heading as the follower nears its
        // slot - see setChaseReference. Aiming hard at the slot the whole way in is what makes a
        // follower cut across/through the leader on final approach instead of falling in alongside it.
        double mirrorWeight = chaseMirrorWeight(cfg);

        float desiredYawToSlot = (float) (MathHelper.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float desiredYaw;
        if (chaseMode && mirrorWeight > 0.0D) {
            float delta = MathHelper.wrapDegrees(desiredYawToSlot - chaseLeaderYaw);
            desiredYaw = chaseLeaderYaw + delta * (float) (1.0D - mirrorWeight);
        } else {
            desiredYaw = desiredYawToSlot;
        }
        desiredYaw += avoidYawBias;
        float yaw = approachDegrees(mob.rotationYaw, desiredYaw, (float) cfg.maxTurnPerTick);

        float pitch = computePitch(dh) - avoidPitchBias;
        // The steep-climb override aims well past the normal glide climb limit, so it needs its own
        // (much lower) floor here - the ordinary bound exists only as slack for avoidPitchBias.
        float pitchFloor = steepClimbTicks > 0
                ? (float) -cfg.steepClimbMaxPitch
                : (float) -cfg.maxClimbPitch - 20.0F;
        pitch = MathHelper.clamp(pitch, pitchFloor, (float) cfg.maxDivePitch);
        // Ease into the new nose angle instead of snapping to it. computePitch can swing from a full
        // dive to a flare in a single tick (a clearance reading crossing flareHeight, an obstacle bias
        // switching on), which both flies badly and reads as a twitching model. A steep-climb override
        // is the deliberate exception: vanilla's firework boost locks onto whichever way the entity is
        // looking within its first couple of ticks, so a graceful multi-tick turn toward vertical would
        // waste most of the boost on a shallow angle - it needs to get there almost immediately instead.
        float pitchRate = steepClimbTicks > 0 ? (float) cfg.steepClimbPitchChangePerTick : (float) cfg.maxPitchChangePerTick;
        pitch = approachDegrees(mob.rotationPitch, pitch, pitchRate);

        avoidYawBias *= AVOID_DECAY;
        avoidPitchBias *= AVOID_DECAY;

        mob.rotationYaw = yaw;
        mob.rotationYawHead = yaw;
        mob.renderYawOffset = yaw;
        mob.rotationPitch = pitch;
    }

    /**
     * How much of the chase steering (pitch and yaw both) should come from mirroring the leader's own
     * attitude rather than aiming straight at the slot: 1.0 once within {@code chaseMirrorRadius} of
     * it, fading to 0.0 by {@code chaseMirrorRadius + chaseMirrorFadeDistance}. Only meaningful in
     * chase mode; callers must check that separately since this has no opinion on the non-chase case.
     */
    private double chaseMirrorWeight(TravellersConfig.Elytra cfg) {
        double fadeStart = cfg.chaseMirrorRadius;
        double fadeSpan = Math.max(1.0D, cfg.chaseMirrorFadeDistance);
        return MathHelper.clamp(1.0D - (chaseSlotDistance - fadeStart) / fadeSpan, 0.0D, 1.0D);
    }

    /**
     * Nose angle for this tick. Outside chase mode, the controller compares the height it still has
     * per block of remaining distance against what a steady glide actually costs: height to spare
     * means dive (faster), running short means flatten out and stretch the glide.
     */
    private float computePitch(double horizontalDistance) {
        TravellersConfig.Elytra cfg = TravellersConfig.ELYTRA;

        double clearance = groundClearance();
        boolean nearGround = clearance >= 0.0D && clearance < cfg.flareHeight;

        // In chase mode, correct toward the target's actual elevation - climb OR dive - instead of the
        // ordinary flare/glide-ratio logic below, as long as there's still real clearance underneath
        // (the ground-proximity flare always wins regardless). A follower's target sits at a fixed ring
        // radius around a moving leader, so horizontalDistance is small almost all the time by
        // construction - the ordinary "close horizontally = arriving, flare for a soft landing" reading
        // below would otherwise apply constantly and pin the nose near level no matter how far above or
        // below the leader the follower actually is.
        //
        // The correction itself is a plain linear response to the vertical error in blocks, not an
        // angle aimed straight at the target (the previous atan2 formula): with a small ring radius as
        // the horizontal distance, even a modest few-block vertical mismatch produced a huge angle -
        // hence the follower alternately overshooting past the leader and needing a rocket to correct
        // back. And once genuinely close (chaseMirrorWeight), the corrective term fades out in favour
        // of simply matching the leader's own current pitch - two entities flying the same nose angle
        // move at the same rate, so a follower already alongside its leader just stays there instead of
        // sliding past it, and one whose leader suddenly dives to land follows the dive immediately
        // instead of circling down slowly behind.
        if (chaseMode && !nearGround && !forceLanding && target != null) {
            double vertical = target.y - mob.posY;
            flaring = false;
            double maxUp = steepClimbTicks > 0 ? cfg.steepClimbMaxPitch : cfg.maxClimbPitch;
            double correctivePitch = -MathHelper.clamp(vertical * cfg.chasePitchGainDegPerBlock, -cfg.maxDivePitch, maxUp);

            double mirrorWeight = chaseMirrorWeight(cfg);
            if (mirrorWeight > 0.0D) {
                return (float) (mirrorWeight * chaseLeaderPitch + (1.0D - mirrorWeight) * correctivePitch);
            }
            return (float) correctivePitch;
        }

        flaring = nearGround || horizontalDistance < cfg.flareDistance;

        if (flaring) return (float) -cfg.maxClimbPitch;
        // Ending the flight early: descend deliberately rather than stretching the glide, and let the
        // flare above take over once the ground is close.
        if (forceLanding) return (float) (cfg.maxDivePitch * 0.35D);

        double altitudeAbove = target != null ? mob.posY - target.y : 0.0D;
        double availableSlope = horizontalDistance > 1.0D ? altitudeAbove / horizontalDistance : 10.0D;
        double glideSlope = 1.0D / Math.max(0.1D, cfg.glideRatio);

        double t = MathHelper.clamp(availableSlope / glideSlope, 0.0D, 2.0D) * 0.5D;
        return (float) (-cfg.maxClimbPitch + (cfg.maxDivePitch + cfg.maxClimbPitch) * t);
    }

    /**
     * Blocks of clear air below the entity, or -1 when the ground below isn't loaded and no flare
     * decision can honestly be made.
     */
    private double groundClearance() {
        int limit = MathHelper.ceil(TravellersConfig.ELYTRA.flareHeight) + 2;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos(
                MathHelper.floor(mob.posX), MathHelper.floor(mob.posY), MathHelper.floor(mob.posZ));

        if (!mob.world.isBlockLoaded(probe)) return -1.0D;

        for (int i = 0; i <= limit; i++) {
            int y = MathHelper.floor(mob.posY) - i;
            if (y < 0) return -1.0D;
            probe.setPos(probe.getX(), y, probe.getZ());
            if (mob.world.getBlockState(probe).getMaterial().blocksMovement()) {
                return mob.posY - (y + 1);
            }
        }
        return limit;
    }

    /**
     * Look ahead along the flight path. Terrain in the way biases the nose up and, if a clear side
     * exists, steers toward it. Throttled by {@code lookAheadIntervalTicks} and only ever runs while
     * actually airborne, so the cost is bounded.
     */
    private void probeObstacles() {
        TravellersConfig.Elytra cfg = TravellersConfig.ELYTRA;

        Vec3d eye = mob.getPositionEyes(1.0F);
        Vec3d heading = new Vec3d(mob.motionX, mob.motionY, mob.motionZ);
        if (heading.lengthSquared() < 1.0E-4D) heading = mob.getLookVec();
        if (heading.lengthSquared() < 1.0E-4D) return;
        heading = heading.normalize();

        if (!isBlocked(eye, heading, cfg.lookAheadDistance)) {
            return;
        }

        avoidPitchBias = 25.0F;

        Vec3d left = rotateY(heading, AVOID_PROBE_ANGLE);
        Vec3d right = rotateY(heading, -AVOID_PROBE_ANGLE);
        boolean leftBlocked = isBlocked(eye, left, cfg.lookAheadDistance);
        boolean rightBlocked = isBlocked(eye, right, cfg.lookAheadDistance);

        if (leftBlocked && !rightBlocked) {
            avoidYawBias = AVOID_PROBE_ANGLE;
        } else if (rightBlocked && !leftBlocked) {
            avoidYawBias = -AVOID_PROBE_ANGLE;
        } else {
            // Both sides (or neither) look the same - commit to one side deterministically so the
            // entity doesn't dither left/right on successive probes.
            avoidYawBias = (mob.getEntityId() & 1) == 0 ? AVOID_PROBE_ANGLE : -AVOID_PROBE_ANGLE;
        }

        debug("obstacle ahead, pitch bias %.0f yaw bias %.0f", avoidPitchBias, avoidYawBias);
    }

    private boolean isBlocked(Vec3d from, Vec3d direction, double distance) {
        Vec3d to = from.add(direction.scale(distance));
        RayTraceResult hit = mob.world.rayTraceBlocks(from, to, false, true, false);
        return hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK;
    }

    private static Vec3d rotateY(Vec3d v, float degrees) {
        float rad = degrees * ((float) Math.PI / 180.0F);
        float cos = MathHelper.cos(rad);
        float sin = MathHelper.sin(rad);
        return new Vec3d(v.x * cos + v.z * sin, v.y, v.z * cos - v.x * sin);
    }

    private static float approachDegrees(float current, float desired, float maxStep) {
        float delta = MathHelper.wrapDegrees(desired - current);
        return current + MathHelper.clamp(delta, -maxStep, maxStep);
    }

    // ---- landing ----

    private void land(String why) {
        if (TravellersConfig.ELYTRA.preventLandingFallDamage) {
            mob.fallDistance = 0.0F;
        }
        // "touched down"/"ditched in liquid" mean the entity was already on the ground or in a liquid
        // when the flight ended - nothing left to fall. Every other reason (elytra breaking mid-air,
        // the hard flight-time cap) can fire at any altitude and leaves the entity with no more glide
        // control at all - a real, undignified freefall with no way to prevent it, but it must not also
        // be a lethal one. See isPendingSafeDescent/EntityTraveller.fall().
        if (!"touched down".equals(why) && !"ditched in liquid".equals(why)) {
            pendingSafeDescent = true;
        }
        // Unconditionally, not just when onGround: vanilla clears its own flag on ground contact but
        // never in water, so a flight that ends in a river would leave the entity flagged as flying
        // for the rest of its life. land() is only ever called once the flight is genuinely over.
        mob.setElytraFlying(false);
        debug("landed (%s) after %d ticks", why, flightTicks);

        state = State.IDLE;
        target = null;
        forceLanding = false;
        flaring = false;
        steepClimbTicks = 0;
        chaseMode = false;
        chaseSlotDistance = Double.MAX_VALUE;
        avoidYawBias = 0.0F;
        avoidPitchBias = 0.0F;
        cooldownTicks = TravellersConfig.ELYTRA.landingCooldownTicks;
    }

    /** See {@link #land}. Consumed (and cleared) by {@code EntityTraveller.fall()} on the next touchdown. */
    public boolean isPendingSafeDescent() {
        return pendingSafeDescent;
    }

    public void clearPendingSafeDescent() {
        pendingSafeDescent = false;
    }

    private void debug(String format, Object... args) {
        if (!TravellersConfig.ELYTRA.debugElytra) return;
        net.minecraftforge.fml.common.FMLLog.log(
                org.apache.logging.log4j.Level.INFO,
                "[Travellers/Elytra] " + mob.getName() + "#" + mob.getEntityId() + ": " + format,
                args);
    }
}
