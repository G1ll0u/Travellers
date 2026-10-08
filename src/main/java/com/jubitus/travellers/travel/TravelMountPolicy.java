package com.jubitus.travellers.travel;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.mount.MountMode;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.pathing.TravellerNavigateGround;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;

/** Mounted travel: riding straight at the waypoint, and leading the horse through terrain it cannot path. */
final class TravelMountPolicy {

    private final EntityAITravel ai;
    private final EntityTraveller mob;

    TravelMountPolicy(EntityAITravel ai, EntityTraveller mob) {
        this.ai = ai;
        this.mob = mob;
    }

    /** Ticks between two rider-sized probes, so a traveller failing to path every tick does not run a
     * second full search every tick alongside it. */
    private static final int PATH_FAILURE_PROBE_INTERVAL = 40;

    private int nextPathFailureProbeTick;

    /**
     * Gets off the horse when the horse is the reason the route fails, and leads it through on foot.
     *
     * <p>The trigger is a measurement, not a guess: {@link TravellerNavigateGround#canReachOnFootSized}
     * re-runs the same search with the mount sizing switched off, so "the destination is reachable on
     * foot but not mounted" is established rather than inferred. Anything else - a genuinely
     * unreachable destination, an awkward but passable slope - leaves the rider in the saddle.
     *
     * <p>This is the honest answer to a whole class of terrain rather than a fix for one shape of it.
     * A horse occupies a two-cell footprint, and vanilla's node model requires every cell of that
     * footprint to be walkable at once - so a doorway one block wide, or a wall one block thick that
     * a real horse would simply step across, is impassable to the planner however the sizing is
     * tuned. Chasing those cases inside the footprint model is how this system grew its previous
     * complexity. Stepping down and walking is what a rider does, costs nothing, and covers all of
     * them at once.
     *
     * @param force skip the "several attempts" cushion below and check immediately. Only meant for
     *     the fast no-path rescue's own last-instant look, where the entity has already been
     *     completely motionless for a full 3 seconds - stronger evidence than that cushion exists to
     *     filter out. The normal per-search path (a failed {@code navigateTo}) always passes
     *     {@code false}: one unusable route is ordinary and a lateral detour usually settles it.
     */
    void considerTerrainDismount(BlockPos waypoint, boolean force) {
        if (mob.world.isRemote || waypoint == null) return;
        if (!TravellersConfig.MOUNTS.terrainDismountEnabled) return;
        if (!mob.isMountedOnBondedMount()) return;
        // A path search launched from a horse still rising out of a jump routinely comes back empty -
        // the node the search starts from is mid-air, past the ground it just left and short of
        // wherever it lands. That is not evidence the horse can't get through; it is evidence the
        // search asked the question at the wrong instant. A trace caught exactly this: a horse jumped
        // a step mid-climb, and while still ascending (posY rising, onGround false) the very next
        // failed search dismounted it into landing on foot, one tick after it had already jumped the
        // hard part. Let it land before judging whether the ground genuinely defeats it.
        Entity mount = mob.getRidingEntity();
        if (mount != null && !mount.onGround) return;
        // Stepping down is a last resort, not a first reaction. One unusable route is ordinary -
        // rough ground produces them and a lateral detour usually settles it. Dismounting on the
        // first one made a rider get off wherever a detour used to fire, which is both wrong and
        // conspicuous. Only a situation that survives several attempts is really about the horse.
        if (!force && ++unusableRouteStreak < UNUSABLE_ROUTES_BEFORE_DISMOUNT) return;
        if (mob.ticksExisted < nextPathFailureProbeTick) return;
        nextPathFailureProbeTick = mob.ticksExisted + PATH_FAILURE_PROBE_INTERVAL;
        if (!(mob.getNavigator() instanceof TravellerNavigateGround)) return;

        boolean onFoot = ((TravellerNavigateGround) mob.getNavigator()).canReachOnFootSized(waypoint);
        com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "path",
                "no usable route to " + waypoint.getX() + "," + waypoint.getY() + "," + waypoint.getZ()
                        + " mounted=no onFootSized=" + (onFoot ? "YES (footprint is the blocker)" : "no (unreachable either way)"));
        if (!onFoot) return;

        unusableRouteStreak = 0;
        // Also arms the shared time+distance self-heal back to MOUNTED (MountModes.considerTerrainFallbackRemount),
        // ticked from TravellerMountSupport.tickMount() whichever AI task is running.
        TravellerMountSupport.enterFallbackLed(mob, com.jubitus.travellers.debug.profile.MovementAnomaly.LedReason.TERRAIN,
                com.jubitus.travellers.notify.NotificationDefs.MOUNT_LED_TERRAIN_BLOCKED);
        terrainLedByThisTask = true;
        com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "mount",
                "dismounting to lead the horse through terrain it cannot path");
        ai.paths.clearNav();
    }

    /** Consecutive route attempts that produced nothing usable. Reset by any route that does. */
    private static final int UNUSABLE_ROUTES_BEFORE_DISMOUNT = 3;

    int unusableRouteStreak;
    /** This task put the rider in a terrain LED that is still armed; undone when the task stops. */
    private boolean terrainLedByThisTask;

    /** Ticks direct riding stays off after a refused corridor, so the planner's route actually gets
     * walked instead of being recomputed and discarded at every clear/blocked boundary. */
    private static final int DIRECT_RIDE_RETRY_TICKS = 20;

    private int directRideRetryAt;
    private BlockPos lastDirectRideWaypoint;

    /**
     * How a mounted traveller normally travels: straight at the waypoint, the way a player rides -
     * committing to one proven stretch of ground at a time rather than a planned route. The aim
     * point rolls ahead of the horse by {@code MOUNTS.mountedDirectLegBlocks} along the bearing to
     * the waypoint, and {@link TravellerNavigateGround#steerDirect} proves that stretch with the
     * same footprint corridor the planner uses (terrain-following, ford-depth water, full-body
     * sweep) before committing, re-proving as the horse advances. Slopes ride as slopes - up to the
     * corridor's total-rise budget per stretch, any rideable descent - and anything the stretch
     * can't take (a 2-block ledge, a tree line, deep water) refuses the corridor and hands travel
     * to the A* planner, whose own hop candidates and the reactive jump take over. A refusal backs
     * off for {@link #DIRECT_RIDE_RETRY_TICKS} so the planner's route is walked, not thrashed.
     *
     * <p>This is deliberately first, not a no-path fallback: over open or mountainous ground the
     * planner's partial best-effort routes each end somewhere slightly different, and walking one
     * after another reads as looping and zigzagging. The straight line needs no route at all.
     *
     * <p>The aim point is quantized to block centres so {@code steerDirect}'s cached corridor
     * verdict holds between ticks instead of re-proving on every fraction of drift.
     */
    boolean tryMountedDirectRide() {
        if (!mob.isMountedOnBondedMount()) return false;
        if (ai.currentWaypoint == null || ai.isInBoat()) return false;
        // A refusal only speaks for the leg it refused - a new waypoint is a new bearing, so the
        // backoff doesn't carry over. Without this, the rider goes planner-mode for up to a second
        // after every waypoint switch near whatever refused the previous leg, which read as a
        // hesitation or a little loop at each waypoint.
        if (!ai.currentWaypoint.equals(lastDirectRideWaypoint)) {
            lastDirectRideWaypoint = ai.currentWaypoint;
            directRideRetryAt = 0;
        }
        if (mob.ticksExisted < directRideRetryAt) return false;
        if (!(mob.getNavigator() instanceof TravellerNavigateGround)) return false;

        double dx = ai.currentWaypoint.getX() + 0.5D - mob.posX;
        double dz = ai.currentWaypoint.getZ() + 0.5D - mob.posZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 2.0D) return false;

        double leg = Math.min(dist, TravellersConfig.MOUNTS.mountedDirectLegBlocks);
        double ax = Math.floor(mob.posX + dx / dist * leg) + 0.5D;
        double az = Math.floor(mob.posZ + dz / dist * leg) + 0.5D;

        TravellerNavigateGround nav = (TravellerNavigateGround) mob.getNavigator();
        if (!nav.steerDirect(ax, az, ai.getActiveSpeed())) {
            directRideRetryAt = mob.ticksExisted + DIRECT_RIDE_RETRY_TICKS;
            return false;
        }
        // Riding straight IS the route working - the planner's failure streak must not survive it,
        // or a rough patch minutes ago still counts toward a dismount here.
        unusableRouteStreak = 0;
        return true;
    }

    /** Never leave a rider on foot because of terrain this task is no longer routing through. */
    void onTaskStop() {
        // Only the terrain LED this task chose itself; a sidestep give-up, water or melee LED is not ours to undo.
        if (terrainLedByThisTask && mob.mountState.getLedFallbackSinceTick() >= 0) {
            TravellerMountSupport.setMountMode(mob, MountMode.MOUNTED);
        }
        terrainLedByThisTask = false;
    }
}
