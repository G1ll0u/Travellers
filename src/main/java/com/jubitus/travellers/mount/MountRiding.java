package com.jubitus.travellers.mount;

import com.jubitus.travellers.unit.UnitSpeed;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import java.util.UUID;

/** Mounted riding physics: reactive and gap jumps, sidestepping a wedge, mounted speed. Reached through {@link TravellerMountSupport}. */
final class MountRiding {

    private MountRiding() {
    }

    /**
     * Nudges a jammed horse sideways when the jump system has nothing to offer - a tree trunk, a
     * low branch, anything that isn't a climbable ledge in front of it. {@link #tickMountedJump}
     * only ever tries going up; a horse pinned by something that was never a "step" in the first
     * place (traces showed this against tree canopies and even against a node that was <em>lower</em>
     * than the horse, where a jump makes no sense at all) just sat there rejecting jump attempts
     * every tick until the multi-second stuck-recovery ladder eventually intervened.
     *
     * <p>Deliberately gated on several consecutive wedged ticks ({@code
     * MOUNTS.mountedSidestepTriggerTicks}), not the first one - a single-tick
     * brush against something the horse was already clearing on its own should not trigger a shove.
     * The counter lives on the rider (see {@code EntityTraveller.mountWedgedTicks}), transient like
     * the mount jump yaw next to it - never persisted, reset the instant the horse is not wedged.
     *
     * <p><b>A repeated sidestep is not a fix, it is evidence the spot itself is bad.</b> A trace
     * caught a horse under dense canopy sidestepping the same direction every ~20 ticks for 400+
     * ticks straight, each time curving back into the exact same wedge - open ground to the side was
     * never the problem, so shoving it sideways over and over just re-enacted the same short loop.
     * {@code EntityTraveller.sidestepStreak} counts sidesteps that fire close together in time
     * ({@code MOUNTS.mountedSidestepGiveUpWindowTicks}); past {@code
     * MOUNTS.mountedSidestepGiveUpAfter} of them the horse stops trying to ride through this spot at
     * all and steps down to lead it on foot instead ({@link #setMountMode}) - the same LED mode every
     * other "the horse cannot path this" case already uses, with its own existing remount-on-progress
     * logic to get back in the saddle once clear of whatever this was.
     */
    static void trySidestepIfWedged(EntityTraveller rider, EntityHorse horse) {
        if (!TravellersConfig.MOUNTS.mountedSidestepEnabled) return;

        if (!isMountWedged(rider)) {
            rider.mountState.setMountWedgedTicks(0);
            rider.mountState.setMountWedgeAnchorYaw(Float.NaN);
            return;
        }

        int wedgedTicks = rider.mountState.getMountWedgedTicks() + 1;
        rider.mountState.setMountWedgedTicks(wedgedTicks);
        // Latch the direction once, at the start of this wedge episode, and hold it - see the field
        // doc on EntityTraveller.mountWedgeAnchorYaw for why re-sampling every tick was the bug.
        if (Float.isNaN(rider.mountState.getMountWedgeAnchorYaw())) {
            rider.mountState.setMountWedgeAnchorYaw(horse.rotationYaw);
        }
        if (wedgedTicks < TravellersConfig.MOUNTS.mountedSidestepTriggerTicks) return;

        int ticksSinceLast = rider.ticksExisted - rider.mountState.getLastSidestepTick();
        int streak = (rider.mountState.getLastSidestepTick() > 0
                && ticksSinceLast <= TravellersConfig.MOUNTS.mountedSidestepGiveUpWindowTicks)
                ? rider.mountState.getSidestepStreak() + 1 : 1;

        if (streak >= TravellersConfig.MOUNTS.mountedSidestepGiveUpAfter) {
            rider.mountState.setSidestepStreak(0);
            rider.mountState.setMountWedgedTicks(0);
            rider.mountState.setMountWedgeAnchorYaw(Float.NaN);
            com.jubitus.travellers.debug.trace.TravellerTracer.event(rider, "mount",
                    "giving up on sidestepping this spot (" + streak + " tries) - leading horse on foot instead, "
                            + obstacleForTrace(rider, horse));
            MountModes.enterFallbackLed(rider, com.jubitus.travellers.debug.profile.MovementAnomaly.LedReason.SIDESTEP_GAVE_UP,
                    com.jubitus.travellers.notify.NotificationDefs.MOUNT_LED_SIDESTEP_GAVE_UP);
            return;
        }
        rider.mountState.setSidestepStreak(streak);
        rider.mountState.setLastSidestepTick(rider.ticksExisted);

        // Same latched yaw the trigger above captured, not a fresh sample - see the field doc on
        // EntityTraveller.mountWedgeAnchorYaw.
        double yaw = rider.mountState.getMountWedgeAnchorYaw() * 0.017453292F;
        double fwdX = -MathHelper.sin((float) yaw);
        double fwdZ = MathHelper.cos((float) yaw);
        // Perpendicular to the current heading - the two candidate sidestep directions.
        double rightX = fwdZ;
        double rightZ = -fwdX;

        double half = horse.width / 2.0D;
        double probe = half + 1.0D;
        // Alternate which side is tried first between attempts. A fixed "right first" preference is
        // exactly what produced the repeating loop above: the same side being clear every time just
        // re-walks the same little detour back into the same wedge.
        boolean tryRightFirst = (streak % 2) == 1;
        boolean primaryClear = lateralSpotClear(horse, rightX * probe * (tryRightFirst ? 1 : -1),
                rightZ * probe * (tryRightFirst ? 1 : -1));
        boolean secondaryClear = !primaryClear && lateralSpotClear(horse,
                rightX * probe * (tryRightFirst ? -1 : 1), rightZ * probe * (tryRightFirst ? -1 : 1));
        if (!primaryClear && !secondaryClear) return; // both sides blocked too - nothing a shove can fix here

        boolean wentRight = primaryClear == tryRightFirst;
        double sign = wentRight ? 1.0D : -1.0D;
        double impulse = TravellersConfig.MOUNTS.mountedSidestepImpulse;
        horse.motionX += rightX * sign * impulse;
        horse.motionZ += rightZ * sign * impulse;
        // A small forward component too, so the nudge actually clears the obstacle's corner instead
        // of just sliding along its face.
        horse.motionX += fwdX * impulse * 0.3D;
        horse.motionZ += fwdZ * impulse * 0.3D;

        com.jubitus.travellers.debug.profile.MovementProfiler.anomaly(rider, com.jubitus.travellers.debug.profile.MovementAnomaly.MOUNT_SIDESTEP);
        com.jubitus.travellers.debug.trace.TravellerTracer.event(rider, "mount",
                String.format(java.util.Locale.ROOT, "sidestep %s (wedged %d ticks, streak %d) %s",
                        wentRight ? "right" : "left", wedgedTicks, streak, obstacleForTrace(rider, horse)));

        // Give the nudge a few ticks to actually move the horse before judging it wedged again -
        // isMountWedged would otherwise very likely still read true the very next tick (motion takes
        // a tick to show up in position), retriggering immediately and stacking impulses.
        rider.mountState.setMountWedgedTicks(TravellersConfig.MOUNTS.mountedSidestepTriggerTicks - 4);
    }

    /** Whether the horse's own body (not the rider's) would fit standing at its current height,
     * offset sideways by ({@code dx}, {@code dz}) - the one physical question a sidestep needs
     * answered, real continuous volume rather than a per-column approximation. */
    static boolean lateralSpotClear(EntityHorse horse, double dx, double dz) {
        AxisAlignedBB box = horse.getEntityBoundingBox().offset(dx, 0.0D, dz);
        return !horse.world.collidesWithAnyBlock(box);
    }

    /**
     * Jumps a step the horse cannot walk up, when it is already pressed against it.
     *
     * <p>Jumping is the one thing vanilla never does for a non-player rider:
     * {@code AbstractHorse.setJumpPower(int)} is {@code @SideOnly(CLIENT)} - it exists to turn the
     * client's jump-bar input into a packet - so server AI must write the same impulse
     * {@code travel()} would, directly. The height comes from this individual horse's own
     * JUMP_STRENGTH attribute, so a weak horse never attempts what a strong one can clear.
     *
     * <p><b>Deliberately reactive, not planned.</b> It fires only once the horse is actually in
     * contact with the obstacle ({@code collidedHorizontally}) and the steering is asking for a
     * height it cannot step to. Teaching the pathfinder to route over walls instead would mean
     * committing to jumps before knowing whether the landing works out, and a route that assumes a
     * jump the horse then fails strands it somewhere worse than where it started. Here the worst case
     * is a jump that does not clear, after which nothing has changed and the ordinary stuck
     * escalation continues. This is the case the traces kept showing: a horse driving into a 2-block
     * face and stopping there, when the horse under it could have hopped it.
     *
     * <p>No cooldown is needed. Vanilla clears {@code isHorseJumping()} on landing (in the steered
     * branch of {@code travel()}), so at most one jump is in flight at a time.
     */
    static void releaseJumpYawOnLanding(EntityTraveller rider, EntityHorse horse) {
        // Back on the ground: hand steering back (see TravellerMoveHelper).
        // `horse.onGround` alone is not enough - a trace caught it reading true for one tick while
        // the horse was still clearly climbing (posY rising for several more ticks after), releasing
        // the freeze mid-ascent and letting the capped-turn steering drag the whole arc into a circle
        // (the orbit slowForTurn's own doc describes) before it ever actually landed. Require the
        // vertical motion to have stopped rising too, so a stale/spurious onGround reading mid-jump
        // can't end the freeze early.
        if (horse.onGround && horse.motionY <= 0.0D && !Float.isNaN(rider.mountState.getMountJumpYaw())) {
            rider.mountState.setMountJumpYaw(Float.NaN);
        }
    }

    static void tickMountedJump(EntityTraveller rider, EntityHorse horse) {
        if (!TravellersConfig.MOUNTS.mountedJumpEnabled) return;
        if (!TravellersConfig.MOUNTS.mountedReactiveJumpEnabled) return;
        if (!horse.onGround || horse.isHorseJumping()) return;
        if (!horse.collidedHorizontally || rider.moveForward <= 0.02F) return;

        double feet = horse.getEntityBoundingBox().minY;
        com.jubitus.travellers.pathing.MountTerrainProfile.ClimbTarget target = obstacleTopAhead(rider, horse, feet);
        if (target == null) {
            traceJumpRejection(rider, "no climbable column within lookahead (open ground ahead, or the "
                    + "nearest solid thing is out of reach)");
            return;
        }

        double rise = target.landingY - feet;
        if (rise <= horse.stepHeight + 1.0E-3D) return;

        // The obstacle scan only ever validates the LANDING column - it says nothing about the space
        // directly above where the horse is standing right now, which is exactly what the jump's own
        // ascent has to pass through. A horse standing under low branches has room to stand but not to
        // rise: a trace caught this firing the same doomed jump every few ticks forever, bouncing
        // straight up into the canopy and falling back onto the exact same spot, net progress zero,
        // because nothing here asked "is there room to go UP from here" separately from "is there room
        // to land over there". Checked with the horse's own real body, not a single point.
        AxisAlignedBB current = horse.getEntityBoundingBox();
        AxisAlignedBB liftoffPath = new AxisAlignedBB(current.minX, current.minY, current.minZ,
                current.maxX, current.minY + rise + JUMP_CLEARANCE_MARGIN, current.maxZ);
        if (horse.world.collidesWithAnyBlock(liftoffPath)) {
            traceJumpRejection(rider, "canopy or overhang directly overhead blocks the jump's own ascent, "
                    + "even though the landing beyond it is clear");
            return;
        }

        // Sized from the obstacle just cleared, not the horse's full strength - a player clearing a
        // 2-block step doesn't jump the same as one clearing a 4-block one, and neither should this.
        // requiredJumpVelocity runs the same discrete physics jumpHeightOf itself relies on, so the
        // planned rise and the executed impulse can never disagree about what this horse can do.
        double impulse = requiredJumpVelocity(rise + JUMP_CLEARANCE_MARGIN, horse.getHorseJumpStrength());
        horse.motionY = impulse;
        // The same forward kick vanilla gives a jumping horse, so it lands on top of the obstacle
        // rather than sliding back down its face.
        float sin = MathHelper.sin(horse.rotationYaw * 0.017453292F);
        float cos = MathHelper.cos(horse.rotationYaw * 0.017453292F);
        horse.motionX += -0.4F * sin;
        horse.motionZ += 0.4F * cos;
        horse.setHorseJumping(true);
        horse.isAirBorne = true;
        rider.mountState.setMountJumpYaw(horse.rotationYaw);

        com.jubitus.travellers.debug.trace.TravellerTracer.event(rider, "mount",
                String.format(java.util.Locale.ROOT,
                        "jump rise=%.2f impulse=%.2f capability=%.2f strength=%.2f columnsAhead=%d",
                        rise, impulse, jumpHeightOf(horse), horse.getHorseJumpStrength(), target.columnsAhead));
    }

    /**
     * Jumps a hole or stream the planner routed across ({@code TravellerNodeProcessor.addMountGapCandidates}):
     * fires once the column under the horse's leading edge has no dry ground and the steering target
     * lies beyond dry ground within {@code MOUNTS.mountedMaxGapWidth}. Cardinal only, like the planner,
     * so the scanned line is the flown line. The impulse is sized from vanilla's airborne physics
     * (gravity, 0.91 horizontal drag) to land the whole body on the far rim.
     */
    static void tickMountedGapJump(EntityTraveller rider, EntityHorse horse) {
        int maxGap = TravellersConfig.MOUNTS.mountedMaxGapWidth;
        if (!TravellersConfig.MOUNTS.mountedJumpEnabled || maxGap <= 0) return;
        if (!horse.onGround || horse.isHorseJumping() || horse.isInWater()) return;
        if (rider.moveForward <= 0.02F || !rider.getMoveHelper().isUpdating()) return;

        double toX = rider.getMoveHelper().getX() - horse.posX;
        double toZ = rider.getMoveHelper().getZ() - horse.posZ;
        boolean alongX = Math.abs(toX) >= Math.abs(toZ);
        double toAlong = alongX ? toX : toZ;
        if (Math.abs(alongX ? toZ : toX) > GAP_TARGET_MAX_SIDE_OFFSET) return;
        int sg = toAlong > 0 ? 1 : -1;
        double along = alongX ? horse.posX : horse.posZ;
        double side = alongX ? horse.posZ : horse.posX;
        double half = horse.width / 2.0D;
        // A planned jump steers at a node beyond the gap; ordinary walking steers one cell ahead.
        if (Math.abs(toAlong) < half + 1.5D) return;
        double feet = horse.getEntityBoundingBox().minY;

        // One column read per tick on ordinary ground: the one under the middle of the chest.
        int first = MathHelper.floor(along + sg * (half + 0.3D));
        double fx = alongX ? first + 0.5D : side;
        double fz = alongX ? side : first + 0.5D;
        if (!Double.isNaN(com.jubitus.travellers.pathing.MountTerrainProfile.groundYNear(
                horse.world, fx, fz, feet, horse.stepHeight, 1.0D, 2))) {
            return;
        }

        for (int g = 1; g <= maxGap; g++) {
            int col = first + sg * g;
            double near = rimSurface(horse.world, alongX, col, side, half, feet, 1.0D);
            if (Double.isNaN(near)) continue;
            double far = rimSurface(horse.world, alongX, col + sg, side, half, feet, 1.0D);
            if (Double.isNaN(far)) return;
            double landY = Math.max(near, far);
            double landAlong = (sg > 0 ? col : col + 1) + sg * (half + 0.2D);
            double distance = Math.abs(landAlong - along);
            if (Math.abs(toAlong) < distance - half) return; // steering target is not across the gap

            AxisAlignedBB box = horse.getEntityBoundingBox();
            double floor = Math.max(feet, landY) + 0.3D;
            double top = floor + com.jubitus.travellers.pathing.PathBody.clearanceHeight(rider);
            double lo = Math.min(along, landAlong) - half;
            double hi = Math.max(along, landAlong) + half;
            AxisAlignedBB flight = alongX
                    ? new AxisAlignedBB(lo, floor, box.minZ, hi, top, box.maxZ)
                    : new AxisAlignedBB(box.minX, floor, lo, box.maxX, top, hi);
            if (horse.world.collidesWithAnyBlock(flight)) {
                traceJumpRejection(rider, "gap ahead but the flight path is blocked");
                return;
            }

            double strength = horse.getHorseJumpStrength();
            double drop = landY - feet;
            double vy = requiredJumpVelocity(Math.max(0.0D, drop) + GAP_JUMP_APEX, strength);
            double vh = gapJumpHorizontalSpeed(distance, airTicks(vy, drop));
            if (vh > GAP_JUMP_MAX_SPEED) {
                vy = strength;
                vh = gapJumpHorizontalSpeed(distance, airTicks(vy, drop));
                if (vh > GAP_JUMP_MAX_SPEED) {
                    traceJumpRejection(rider, "gap too wide for this horse");
                    return;
                }
            }

            horse.motionY = vy;
            horse.motionX = alongX ? sg * vh : 0.0D;
            horse.motionZ = alongX ? 0.0D : sg * vh;
            horse.setHorseJumping(true);
            horse.isAirBorne = true;
            float yaw = alongX ? (sg > 0 ? -90.0F : 90.0F) : (sg > 0 ? 0.0F : 180.0F);
            horse.rotationYaw = yaw;
            rider.rotationYaw = yaw;
            rider.mountState.setMountJumpYaw(yaw);
            com.jubitus.travellers.debug.trace.TravellerTracer.event(rider, "mount",
                    String.format(java.util.Locale.ROOT, "gap jump width=%d dist=%.2f drop=%.2f vy=%.2f vh=%.2f",
                            g, distance, drop, vy, vh));
            return;
        }
    }

    /**
     * The dry surface under one cross-section of the horse ({@code col} along the axis, its width
     * across), or NaN when any part of it has nothing to stand on between one block below
     * {@code feet} and {@code rise} above.
     */
    static double rimSurface(World world, boolean alongX, int col, double side, double half,
                                     double feet, double rise) {
        double best = Double.NaN;
        for (double s = -half + 0.05D; s <= half - 0.05D + 1.0E-6D; s += half - 0.05D) {
            double x = alongX ? col + 0.5D : side + s;
            double z = alongX ? side + s : col + 0.5D;
            double y = com.jubitus.travellers.pathing.MountTerrainProfile.groundYNear(world, x, z, feet, rise, 1.0D, 2);
            if (Double.isNaN(y)) return Double.NaN;
            if (Double.isNaN(best) || y > best) best = y;
        }
        return best;
    }

    /** Ticks airborne for a launch at {@code vy} landing {@code drop} blocks above takeoff (negative = below). */
    static int airTicks(double vy, double drop) {
        double y = 0.0D;
        for (int t = 1; t <= 60; t++) {
            y += vy;
            vy = (vy - 0.08D) * 0.98D;
            if (vy < 0.0D && y <= drop) return t;
        }
        return 60;
    }

    /** Launch speed covering {@code distance} in {@code ticks} under the 0.91 airborne drag. */
    static double gapJumpHorizontalSpeed(double distance, int ticks) {
        return distance * (1.0D - 0.91D) / (1.0D - Math.pow(0.91D, ticks));
    }

    /** How far beside the jump axis the steering target may sit. */
    static final double GAP_TARGET_MAX_SIDE_OFFSET = 1.5D;

    /** Arc height above the higher rim - enough to clear the far edge with the whole body. */
    static final double GAP_JUMP_APEX = 0.8D;

    /** Fastest launch allowed, blocks/tick - a full-strength horse at a gallop. */
    static final double GAP_JUMP_MAX_SPEED = 0.9D;

    /**
     * Records why a horse pressed against something did NOT jump - the reactive side of the mount
     * jump had no visibility into its own rejections until this existed, which left "why didn't it
     * jump" to guesswork whenever a trace showed zero jump events across a stall. Cheap to call
     * unconditionally: {@code TravellerTracer.isTraced} is a UUID set lookup, and this only runs at
     * all once the horse is already collided and trying to move (see the call sites above).
     */
    static void traceJumpRejection(EntityTraveller rider, String reason) {
        if (!com.jubitus.travellers.debug.trace.TravellerTracer.isTraced(rider.getUniqueID())) return;
        Entity horse = rider.getRidingEntity();
        com.jubitus.travellers.debug.trace.TravellerTracer.event(rider, "mount", "jump rejected: " + reason
                + (horse != null ? " " + obstacleForTrace(rider, horse) : ""));
    }

    /** What the horse is pressed against, for a trace line; empty for an untraced rider. */
    static String obstacleForTrace(EntityTraveller rider, Entity horse) {
        if (!com.jubitus.travellers.debug.trace.TravellerTracer.isTraced(rider.getUniqueID())) return "";
        float yaw = Float.isNaN(rider.mountState.getMountWedgeAnchorYaw())
                ? horse.rotationYaw : rider.mountState.getMountWedgeAnchorYaw();
        return com.jubitus.travellers.debug.trace.MountObstacle.probe(horse, yaw).describe();
    }

    /**
     * The nearest column ahead of the horse a jump could land on, or {@code null} if none qualifies.
     *
     * <p>Measured from the obstacle rather than from the steering target, and that distinction is the
     * whole method. The steering target takes its height from a path node that can be several blocks
     * further along, so on a climb it reads higher the more the route continues to rise - a horse
     * caught against a 2-block step was refused its jump because the node beyond that step sat 3
     * blocks up, one twentieth of a block over its capability. What decides whether a jump helps is
     * the thing in the way, and nothing else.
     *
     * <p>Delegates to {@link com.jubitus.travellers.pathing.MountTerrainProfile}, walking up to
     * {@code MOUNTS.mountedJumpLookaheadColumns} steps ahead rather than only the one column directly
     * in front - a single column reads an overhang or a multi-cell-deep stepped face as "nothing
     * here", even while the horse is visibly jammed against it. Sampled across the horse's width at
     * each step, not just down its centre line, because the case that matters is precisely the
     * asymmetric one: {@code Entity.move} clips each axis independently and never pushes a body
     * sideways, so a single cell that is one block higher under one shoulder stops the whole horse
     * while its centre line reads clear.
     *
     * <p>Scans along the wedge-episode's latched yaw ({@code EntityTraveller.mountWedgeAnchorYaw})
     * when one is active, rather than the horse's live {@code rotationYaw} - the live yaw swings with
     * the bearing to the steering target as the horse's own tiny position jitter shifts it, and a scan
     * direction that changes tick to tick can miss the same obstacle it just found. Falls back to the
     * live yaw when there is no active episode (the ordinary case: a first-contact jump attempt).
     */
    static com.jubitus.travellers.pathing.MountTerrainProfile.ClimbTarget obstacleTopAhead(
            EntityTraveller rider, EntityHorse horse, double feet) {
        float yawDeg = Float.isNaN(rider.mountState.getMountWedgeAnchorYaw()) ? horse.rotationYaw : rider.mountState.getMountWedgeAnchorYaw();
        double yaw = yawDeg * 0.017453292F;
        double dirX = -MathHelper.sin((float) yaw);
        double dirZ = MathHelper.cos((float) yaw);
        double half = horse.width / 2.0D;
        double reach = half + 0.35D;

        return com.jubitus.travellers.pathing.MountTerrainProfile.scanClimbColumns(
                horse.world, horse.posX, horse.posZ, dirX, dirZ, half, reach, feet, jumpHeightOf(horse),
                JUMP_CLEARANCE_MARGIN, TravellersConfig.MOUNTS.mountedJumpLookaheadColumns, 2);
    }

    /**
     * How high this specific horse actually gets from a standing jump, by running vanilla's own
     * vertical physics (gravity 0.08 then 0.98 drag, every tick) from its JUMP_STRENGTH until the
     * arc turns over. A vanilla roll spans roughly 1.6 to 5 blocks, so "can a horse jump two blocks"
     * has no single answer - it has to be asked of the individual.
     */
    /** Height a jump must beat the obstacle by, so the arc carries over it instead of grazing it. */
    static final double JUMP_CLEARANCE_MARGIN = 0.2D;

    static double jumpHeightOf(EntityHorse horse) {
        return jumpHeightForVelocity(horse.getHorseJumpStrength());
    }

    /** The discrete physics {@link #jumpHeightOf} runs, factored out so {@link #requiredJumpVelocity}
     * can probe it at velocities other than the horse's own full strength. */
    static double jumpHeightForVelocity(double v0) {
        double vy = v0;
        double y = 0.0D;
        for (int t = 0; t < 60 && vy > 0.0D; t++) {
            y += vy;
            vy = (vy - 0.08D) * 0.98D;
        }
        return y;
    }

    /**
     * The smallest initial vertical velocity that clears {@code targetRise} blocks under the same
     * discrete physics {@link #jumpHeightOf} uses - found by bisecting on {@link
     * #jumpHeightForVelocity}, since that recurrence has no clean closed-form inverse.
     *
     * <p>This is the whole fix for a jump over a 2-block step looking identical to one over a
     * 4-block step: every jump used to launch at {@code getHorseJumpStrength()} regardless of what it
     * was actually clearing, which looks like a player mashing the jump key at full charge for a
     * kerb. A real rider, and this method, size the jump to the obstacle.
     *
     * <p>Capped at {@code maxStrength} - a target beyond what this horse can ever reach still gets
     * its best (and only) attempt rather than nothing. Callers already refuse the jump entirely
     * before this is reached when the obstacle is out of reach (see {@code
     * MountTerrainProfile.scanClimbColumns}'s own reach limit), so the cap is a safety floor, not the
     * normal path.
     */
    static double requiredJumpVelocity(double targetRise, double maxStrength) {
        if (targetRise <= 0.0D) return 0.0D;
        double lo = 0.0D;
        double hi = maxStrength;
        for (int i = 0; i < 24; i++) {
            double mid = (lo + hi) / 2.0D;
            if (jumpHeightForVelocity(mid) < targetRise) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return Math.min(hi, maxStrength);
    }

    /**
     * True while a mounted rider's horse is pressed against something and going nowhere despite its
     * rider asking to move forward. The single definition of "wedged", shared by the sidestep below
     * and by the follow AI - whose own obstacle probes are all sized from the rider's narrow hitbox
     * and are therefore blind to anything only the horse collides with.
     */
    static boolean isMountWedged(EntityTraveller rider) {
        if (!rider.isMountedOnBondedMount()) return false;
        Entity ride = rider.getRidingEntity();
        if (ride == null) return false;
        if (!ride.collidedHorizontally || rider.moveForward <= 0.02F) return false;
        double dx = ride.posX - ride.prevPosX;
        double dz = ride.posZ - ride.prevPosZ;
        return dx * dx + dz * dz <= 0.0016D;
    }

    /** Never leave the attribute at zero: a horse with no move speed cannot be led or recalled
     * either, and the rider simply commands moveForward = 0 when it wants to stand still anyway. */
    static final double MIN_MOUNTED_SPEED = 0.05D;

    /**
     * Mounted ground speed is a <b>product</b>, and getting that wrong is where every past
     * "much too fast" / "no faster than walking" report came from.
     *
     * <p>{@code EntityMoveHelper} sets an AI mob's speed as {@code speed * MOVEMENT_SPEED}, and
     * {@code EntityLiving.setAIMoveSpeed} writes that same value into {@code moveForward}. So an
     * unmounted traveller's per-tick acceleration is {@code moveForward x its own AI move speed} -
     * the attribute appears twice. {@code AbstractHorse.travel()} then feeds the rider's
     * {@code moveForward} into {@code EntityLivingBase.travel()} but supplies the second factor from
     * the horse's <em>raw</em> attribute ({@code setAIMoveSpeed(getAttributeValue(MOVEMENT_SPEED))}),
     * with none of the per-task multiplier applied. Copying the mod's base walking speed onto the
     * horse therefore did not reproduce walking speed at all - it reproduced it divided by whatever
     * multiplier the current AI task happened to use.
     *
     * <p>Mirroring the rider's own current AI move speed makes the two products identical, so a
     * mounted traveller moves at exactly its on-foot speed for every task, and
     * {@link UnitSpeed#mountedFactor()} is the single boost on top of that.
     */
    static void applyMountedSpeed(EntityTraveller rider, EntityHorse horse) {
        double target = Math.max(MIN_MOUNTED_SPEED, rider.getAIMoveSpeed() * UnitSpeed.mountedFactor());
        net.minecraft.entity.ai.attributes.IAttributeInstance attr =
                horse.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED);
        // Only write on a real change: setBaseValue flags the attribute for a client resync, and
        // this runs every tick for every mounted rider.
        if (Math.abs(attr.getBaseValue() - target) > 1.0E-4D) {
            attr.setBaseValue(target);
        }
    }
}
