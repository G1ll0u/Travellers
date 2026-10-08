package com.jubitus.travellers.travel;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.util.math.BlockPos;

/** Asks the navigator for a path to the current waypoint: budget, cooldowns, stillborn and partial routes. */
final class TravelPathDriver {

    private final EntityAITravel ai;
    private final EntityTraveller mob;

    // If we were denied a global scheduler slot while having noPath,
// don't treat it as "stuck" for a short grace period.
    int lastSchedulerDenyTick = -999999;
    private static final int TRAVEL_PATH_COOLDOWN_TICKS = 15;
    private static final int PATH_COOLDOWN_TICKS = 20;
    private int nextTravelSlotTick = 0;   // scheduler pacing (global)
    int nextComputePathTick = 0;  // actual expensive path compute pacing (per mob)
    int lastPathIndex = -1;
    int pathNoAdvanceTicks = 0;

    TravelPathDriver(EntityAITravel ai, EntityTraveller mob) {
        this.ai = ai;
        this.mob = mob;
    }

    void clearNav() {
        mob.getNavigator().clearPath();
        lastPathIndex = -1;
        pathNoAdvanceTicks = 0;
    }

    void pushPathToWaypoint(boolean force) {
        // Never pathfind when nobody is tracking us.
        if (!mob.world.isRemote && ai.isOffscreen()) {
            lastSchedulerDenyTick = mob.ticksExisted;
            return;
        }
        if (ai.currentWaypoint == null) return;
        // --- NEW: throttle travel path computations (avoid stop-go by NOT clearing path if denied) ---
        if (!canComputeTravelPath(force)) return;

        boolean stuckish =
                (pathNoAdvanceTicks >= 40) || (ai.stuckActions.noMoveTicks >= 40);

        int priority = 0;
        if (mob.getNavigator().noPath()) priority = 2;     // urgent (otherwise freeze)
        else if (stuckish) priority = 1;

        // Ask the global scheduler if we can path this tick
        com.jubitus.travellers.pathing.PathBudget.Result slot = com.jubitus.travellers.pathing.PathBudget.acquire(
                mob.world, com.jubitus.travellers.pathing.PathBudget.Client.TRAVEL, mob.getEntityId(), priority);
        com.jubitus.travellers.debug.profile.MovementProfiler.travelSchedule(priority, slot);
        if (slot != com.jubitus.travellers.pathing.PathBudget.Result.GRANTED) {

            // If we have no path, this denial means we will stand still.
            // Record it so updateTask() won't escalate stuck/teleport just because we're throttled.
            if (mob.getNavigator().noPath()) {
                lastSchedulerDenyTick = mob.ticksExisted;
            }

            // IMPORTANT: do NOT clear path here — keep walking the old path smoothly
            return;
        }

// we got a slot; clear any "denied" mark
        lastSchedulerDenyTick = -999999;

        // Per-mob cooldown so this mob doesn't immediately ask again next tick
        nextTravelSlotTick = mob.ticksExisted + TRAVEL_PATH_COOLDOWN_TICKS;

        // --- NEW: throttle expensive path computations ---
        if (!canComputeNewPath(force)) {
            return;
        }

        // If we're in a boat, don't use Navigator; steer manually.
        if (mob.isRiding() && mob.getRidingEntity() instanceof EntityBoat) {
            ai.boats.steerBoatToWaypoint();
            return;
        }

        // normal land/water navigation
        Path current = mob.getNavigator().getPath();
        if (!force && current != null) {
            PathPoint dest = current.getFinalPathPoint();
            if (dest != null) {
                if (Math.abs(dest.x - ai.currentWaypoint.getX()) <= 1 &&
                        Math.abs(dest.z - ai.currentWaypoint.getZ()) <= 1) return;
            }
        }

        double navSpeed = ai.getActiveSpeed();
        boolean ok = navigateTo(ai.currentWaypoint, navSpeed);

        // A route that goes nowhere is not a route. Pick a different waypoint now rather than
        // re-asking for the same unreachable one until the stuck ladder resorts to a teleport.
        if (ok && isPathStillborn(ai.currentWaypoint) && mob.ticksExisted >= nextStillbornDetourTick) {
            nextStillbornDetourTick = mob.ticksExisted + STILLBORN_DETOUR_INTERVAL;
            com.jubitus.travellers.debug.profile.MovementProfiler.anomaly(mob, com.jubitus.travellers.debug.profile.MovementAnomaly.TRAVEL_STILLBORN);
            com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "path",
                    "stillborn route to " + ai.currentWaypoint.getX() + "," + ai.currentWaypoint.getZ()
                            + " - detouring instead of walking it");
            // A stillborn route is the shape an enclosure produces, so ask the same question here as
            // on an outright failure: is the horse the reason? Detouring around a wall a rider could
            // simply step over would be busywork.
            ai.mounts.considerTerrainDismount(ai.currentWaypoint, false);
            clearNav();
            if (ai.waypoints.tryDetourWide()) {
                ok = navigateTo(ai.currentWaypoint, navSpeed);
            }
        }

// throttle next path attempt
        nextComputePathTick = mob.ticksExisted + PATH_COOLDOWN_TICKS;

        if (ok) {
            lastPathIndex = -1;
            pathNoAdvanceTicks = 0;
            // Only clear the "mounted routing isn't working" streak on real progress. A lone
            // non-stillborn tick used to reset it unconditionally, but an entity oscillating in
            // place between two adjacent path nodes (spinning between idx=0/2 and idx=1/2 at a
            // spot the horse genuinely cannot fit through) reads as "not stillborn" often enough
            // that the streak never reached UNUSABLE_ROUTES_BEFORE_DISMOUNT - so
            // considerTerrainDismount's on-foot check never ran and the rider spun in place
            // indefinitely instead of stepping down to lead the horse through. noMoveTicks is the
            // existing robust "has this entity actually gone anywhere" signal (used by the stuck
            // rescue ladder), so reuse it here instead of the flaky per-tick stillborn read.
            if (!isPathStillborn(ai.currentWaypoint) && ai.stuckActions.noMoveTicks < 20) ai.mounts.unusableRouteStreak = 0;
        }

// If pathfinder refuses, try a shorter hop toward target
        if (!ok && ai.targetVillage != null) {
            int old = ai.stepBlocks;
            ai.stepBlocks = Math.max(12, old / 2);
            BlockPos retry = ai.waypoints.computeNextWaypointToward(ai.targetVillage, ai.stepBlocks);
            ai.stepBlocks = old;

            if (retry != null) {
                ai.currentWaypoint = retry;
                mob.getTravelCommon().setCurrentWaypoint(retry);
                ai.debugMark(retry);
                ok = navigateTo(retry, navSpeed);
            }

            // Both attempts failed and the entity is stuck — try any nearby position to
            // escape enclosed spaces (buildings, etc.) before the teleport system activates.
            if (!ok && !mob.world.isRemote && ai.stuckActions.noMoveTicks >= 40) {
                BlockPos escape = ai.waypoints.findLocalEscapeWaypoint();
                if (escape != null) {
                    ai.currentWaypoint = escape;
                    mob.getTravelCommon().setCurrentWaypoint(escape);
                    navigateTo(escape, navSpeed);
                }
            }
        }

    }

    /** How far a path may end from the waypoint it was asked for before it counts as a partial. */
    private static final double PARTIAL_PATH_TOLERANCE = 3.0D;

    /** A partial route ending this close to where the entity already stands has gained it nothing. */
    private static final double STILLBORN_PATH_RADIUS = 2.5D;

    /** Minimum gap between two stillborn-route detours, so a traveller in a genuinely awkward spot
     * re-aims a couple of times a second at most rather than every path attempt. */
    private static final int STILLBORN_DETOUR_INTERVAL = 40;

    private int nextStillbornDetourTick;

    /**
     * True when the pathfinder answered with a route that neither reaches the waypoint nor gets
     * anywhere: it ends essentially where the entity is standing.
     *
     * <p>Vanilla's {@code PathFinder} never reports failure outright - when the target is unreachable
     * it returns a best-effort route to the closest node it managed to expand, and
     * {@code tryMoveToXYZ} calls that success. Usually that is helpful, since a route that gets part
     * of the way is worth walking. It stops being helpful when the closest reachable node is the one
     * already occupied, and there is nothing in the answer itself to say so.
     *
     * <p>Measured, in mountains: the same waypoint re-requested eight times in a row, each time
     * returning a route ending 39 blocks short at the entity's own position, no movement between
     * them, until the stuck ladder eventually teleported it. Same shape for a worker walled in - it
     * walks to the inside corner nearest the destination and stalls there, which from outside reads
     * as ignoring an open doorway. Both are one missing question: did this route actually gain us
     * anything?
     */
    private boolean isPathStillborn(BlockPos waypoint) {
        net.minecraft.pathfinding.Path path = mob.getNavigator().getPath();
        if (path == null || waypoint == null) return false;
        net.minecraft.pathfinding.PathPoint end = path.getFinalPathPoint();
        if (end == null) return false;

        double missX = (end.x + 0.5D) - (waypoint.getX() + 0.5D);
        double missZ = (end.z + 0.5D) - (waypoint.getZ() + 0.5D);
        if (missX * missX + missZ * missZ <= PARTIAL_PATH_TOLERANCE * PARTIAL_PATH_TOLERANCE) return false;

        double gainX = (end.x + 0.5D) - mob.posX;
        double gainZ = (end.z + 0.5D) - mob.posZ;
        return gainX * gainX + gainZ * gainZ <= STILLBORN_PATH_RADIUS * STILLBORN_PATH_RADIUS;
    }

    /**
     * Records when the pathfinder answered with a route that does not actually reach the waypoint.
     *
     * <p>Vanilla's {@code PathFinder} does not report failure by returning nothing: when it cannot
     * reach the target it hands back a best-effort route to the closest node it managed to touch, and
     * {@code tryMoveToXYZ} reports success. So an entity walled in walks confidently to whichever
     * inside corner is nearest the destination, stops there, and is eventually teleported - which
     * from outside looks exactly like "it ignored the open passage and drove into the corner". A
     * traveller has no reason to look for a way out, because it was never asked to go to the way out:
     * travel waypoints are placed in a straight line toward the village, so in an enclosure they land
     * inside the wall. Nothing in a trace distinguished that from a real pathfinding failure until
     * this line existed.
     */
    private void reportPartialPath(BlockPos waypoint) {
        if (mob.world.isRemote) return;
        if (!com.jubitus.travellers.debug.trace.TravellerTracer.isTraced(mob.getUniqueID())) return;

        net.minecraft.pathfinding.Path path = mob.getNavigator().getPath();
        if (path == null) return;
        net.minecraft.pathfinding.PathPoint end = path.getFinalPathPoint();
        if (end == null) return;

        double dx = (end.x + 0.5D) - (waypoint.getX() + 0.5D);
        double dz = (end.z + 0.5D) - (waypoint.getZ() + 0.5D);
        double miss = Math.sqrt(dx * dx + dz * dz);
        if (miss <= PARTIAL_PATH_TOLERANCE) return;

        com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "path",
                String.format(java.util.Locale.ROOT,
                        "PARTIAL path: asked %d,%d,%d but route ends %d,%d,%d (%.1f blocks short) - waypoint unreachable",
                        waypoint.getX(), waypoint.getY(), waypoint.getZ(), end.x, end.y, end.z, miss));
    }

    /**
     * Calls tryMoveToXYZ with FOLLOW_RANGE clamped to just past the waypoint distance.
     * Prevents PathNavigateGround from creating a ChunkCache extending far beyond the waypoint,
     * which would load chunks along the road well outside the player's view distance.
     */
    private boolean navigateTo(BlockPos waypoint, double speed) {
        net.minecraft.entity.ai.attributes.IAttributeInstance followRangeAttr =
                mob.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.FOLLOW_RANGE);
        double fullRange = followRangeAttr.getBaseValue();
        double dist = Math.sqrt(mob.getDistanceSq(waypoint.getX() + 0.5, waypoint.getY(), waypoint.getZ() + 0.5));
        double safeRange = Math.max(16.0, dist + 8.0);
        boolean clamped = safeRange < fullRange;
        if (clamped) followRangeAttr.setBaseValue(safeRange);
        boolean ok = mob.getNavigator().tryMoveToXYZ(waypoint.getX() + 0.5, waypoint.getY(), waypoint.getZ() + 0.5, speed);
        if (clamped) followRangeAttr.setBaseValue(fullRange);
        if (ok) reportPartialPath(waypoint); else ai.mounts.considerTerrainDismount(waypoint, false);
        if (com.jubitus.travellers.config.TravellersConfig.GAP_JUMP.debugGapJump) {
            net.minecraftforge.fml.common.FMLLog.log(org.apache.logging.log4j.Level.INFO,
                    "[Travellers/GapJump] %s#%d: navigateTo waypoint %d,%d,%d from %.2f,%.2f,%.2f -> pathfind %s",
                    mob.getName(), mob.getEntityId(), waypoint.getX(), waypoint.getY(), waypoint.getZ(),
                    mob.posX, mob.posY, mob.posZ, ok ? "OK" : "FAILED (noPath)");
        }
        return ok;
    }

    void pushPathToWaypoint() {
        pushPathToWaypoint(false);
    }

    private boolean canComputeNewPath(boolean force) {
        if (force) return true;

        // allow if we truly have no path (otherwise mob stops)
        if (mob.getNavigator().noPath()) return true;

        // throttle normal repaths
        return mob.ticksExisted >= nextComputePathTick;
    }

    private boolean canComputeTravelPath(boolean force) {
        if (force) return true;

        // If we have NO path, we must compute one or we will stop.
        if (mob.getNavigator().noPath()) return true;

        // Per-mob cooldown (prevents the same mob asking every tick)
        return mob.ticksExisted >= nextTravelSlotTick;
    }
}
