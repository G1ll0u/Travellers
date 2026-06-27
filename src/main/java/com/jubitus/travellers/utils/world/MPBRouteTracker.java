package com.jubitus.travellers.utils.world;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Follows a pre-planned MPB road route for a single traveller.
 *
 * The route is structured as legs: each leg is a physically contiguous road polyline,
 * and consecutive legs are separated by a village crossing (road ends at one gate,
 * resumes at another). The follower walks each leg with a monotonic cursor and, between
 * legs, explicitly targets the next leg's entry point so the entity crosses villages
 * deliberately instead of drifting toward the final destination.
 *
 * {@link #getNextWaypoint} always returns a concrete target while the route is active;
 * it returns null only when the route is exhausted, empty, or a replan is needed.
 */
public final class MPBRouteTracker {

    private static final int ROUTE_SEARCH_RADIUS = 256;
    /** Waypoints (~4 blocks apart) scanned forward when advancing the cursor. */
    private static final int FORWARD_SCAN = 24;
    /** Entity within this distance of the cursor waypoint counts as having passed it. */
    private static final int WAYPOINT_REACH = 4;
    /** Close enough to a leg waypoint to snap onto the leg (ends transit mode). */
    private static final int JOIN_RADIUS = 10;
    /** Cut over to the next leg's entry when already this close to it. */
    private static final int SHORTCUT_RADIUS = 24;
    /** Entity farther than this from the cursor while following → request a replan. */
    private static final int OFF_ROUTE_RADIUS = 48;
    /** Same target returned this many consecutive times → skip one waypoint (blocked spot). */
    private static final int FORCE_ADVANCE_AFTER = 12;

    private List<List<BlockPos>> legs = Collections.emptyList();
    private int legIndex = 0;
    private int wpIndex = 0;
    /** True while walking toward the current leg's entry point (initial join or village crossing). */
    private boolean transit = false;
    private boolean exhausted = false;
    private boolean replanRequested = false;

    private int routedToX = Integer.MIN_VALUE;
    private int routedToZ = Integer.MIN_VALUE;

    // Blocked-waypoint detection
    private int lastTargetX = Integer.MIN_VALUE;
    private int lastTargetZ = Integer.MIN_VALUE;
    private int sameTargetCount = 0;

    public void planRoute(WorldServer world, int fromX, int fromZ, int toX, int toZ) {
        invalidate();
        routedToX = toX;
        routedToZ = toZ;
        legs = MPBIntegration.findRoadRouteLegs(world, fromX, fromZ, toX, toZ, ROUTE_SEARCH_RADIUS);
        if (legs.isEmpty()) return;

        // Snap the cursor to the nearest waypoint of the first leg
        wpIndex = nearestIndex(legs.get(0), fromX, fromZ, 0, legs.get(0).size());
        transit = distSq(legs.get(0).get(wpIndex), fromX, fromZ) > (long) JOIN_RADIUS * JOIN_RADIUS;
    }

    /** True while the route still has a concrete target to offer (including village transit). */
    public boolean hasRoute() {
        return !legs.isEmpty() && !exhausted && !replanRequested && legIndex < legs.size();
    }

    /** True when the entity strayed too far off-route and the caller should replan. */
    public boolean needsReplan() {
        return replanRequested;
    }

    /**
     * True if a successful plan (even a finished one) targets this destination.
     * Failed plans return false; the caller is responsible for throttling retries.
     */
    public boolean isRouteFor(int toX, int toZ) {
        if (legs.isEmpty()) return false;
        int dx = routedToX - toX;
        int dz = routedToZ - toZ;
        return dx * dx + dz * dz < 16 * 16;
    }

    public void invalidate() {
        legs = Collections.emptyList();
        legIndex = 0;
        wpIndex = 0;
        transit = false;
        exhausted = false;
        replanRequested = false;
        routedToX = Integer.MIN_VALUE;
        routedToZ = Integer.MIN_VALUE;
        lastTargetX = Integer.MIN_VALUE;
        lastTargetZ = Integer.MIN_VALUE;
        sameTargetCount = 0;
    }

    public int routeSize() {
        int n = 0;
        for (List<BlockPos> leg : legs) n += leg.size();
        return n;
    }

    /**
     * Returns the next target on the route (feet Y = road surface Y + 1).
     * In transit mode this is the next leg's entry point — possibly far away; the caller
     * should steer toward it in steps. Returns null when exhausted or a replan is needed.
     */
    @Nullable
    public BlockPos getNextWaypoint(int fromX, int fromZ, int stepBlocks) {
        if (!hasRoute()) return null;

        List<BlockPos> leg = legs.get(legIndex);
        int legEnd = leg.size() - 1;

        if (transit) {
            // Try to snap onto the leg near its entry
            int near = nearestIndex(leg, fromX, fromZ, wpIndex, Math.min(leg.size(), wpIndex + FORWARD_SCAN));
            if (distSq(leg.get(near), fromX, fromZ) <= (long) JOIN_RADIUS * JOIN_RADIUS) {
                wpIndex = near;
                transit = false;
            } else {
                return track(leg.get(wpIndex).up());
            }
        }

        // Monotonic cursor advance within the forward window
        int near = nearestIndex(leg, fromX, fromZ, wpIndex, Math.min(leg.size(), wpIndex + FORWARD_SCAN));
        if (near > wpIndex) {
            wpIndex = near;
            sameTargetCount = 0;
        }
        while (wpIndex < legEnd && distSq(leg.get(wpIndex), fromX, fromZ) <= (long) WAYPOINT_REACH * WAYPOINT_REACH) {
            wpIndex++;
        }

        // Shortcut: already near the next leg's entry → skip the rest of this leg
        if (legIndex + 1 < legs.size()) {
            BlockPos nextEntry = legs.get(legIndex + 1).get(0);
            if (distSq(nextEntry, fromX, fromZ) <= (long) SHORTCUT_RADIUS * SHORTCUT_RADIUS) {
                advanceLeg(fromX, fromZ);
                return getNextWaypoint(fromX, fromZ, stepBlocks);
            }
        }

        // Reached the end of this leg?
        if (distSq(leg.get(legEnd), fromX, fromZ) <= (long) JOIN_RADIUS * JOIN_RADIUS) {
            advanceLeg(fromX, fromZ);
            return getNextWaypoint(fromX, fromZ, stepBlocks);
        }

        // Off-route: pushed too far from the cursor (combat, detour, rescue) → replan
        if (distSq(leg.get(wpIndex), fromX, fromZ) > (long) OFF_ROUTE_RADIUS * OFF_ROUTE_RADIUS) {
            replanRequested = true;
            return null;
        }

        // Blocked waypoint escape: same target returned too many times without passing it
        if (sameTargetCount >= FORCE_ADVANCE_AFTER && wpIndex < legEnd) {
            wpIndex++;
            sameTargetCount = 0;
        }

        int targetIdx = Math.min(wpIndex + Math.max(1, stepBlocks / 4), legEnd);
        return track(leg.get(targetIdx).up());
    }

    private void advanceLeg(int fromX, int fromZ) {
        legIndex++;
        wpIndex = 0;
        sameTargetCount = 0;
        lastTargetX = Integer.MIN_VALUE;
        lastTargetZ = Integer.MIN_VALUE;
        if (legIndex >= legs.size()) {
            exhausted = true;
            return;
        }
        transit = distSq(legs.get(legIndex).get(0), fromX, fromZ) > (long) JOIN_RADIUS * JOIN_RADIUS;
    }

    private BlockPos track(BlockPos target) {
        if (target.getX() == lastTargetX && target.getZ() == lastTargetZ) {
            sameTargetCount++;
        } else {
            lastTargetX = target.getX();
            lastTargetZ = target.getZ();
            sameTargetCount = 0;
        }
        return target;
    }

    private static long distSq(BlockPos p, int x, int z) {
        long dx = p.getX() - x;
        long dz = p.getZ() - z;
        return dx * dx + dz * dz;
    }

    /** Index of the nearest waypoint in leg[start..end), or start when the range is empty. */
    private static int nearestIndex(List<BlockPos> leg, int x, int z, int start, int end) {
        int best = start;
        long bestD2 = Long.MAX_VALUE;
        for (int i = start; i < end; i++) {
            long d2 = distSq(leg.get(i), x, z);
            if (d2 < bestD2) {
                bestD2 = d2;
                best = i;
            }
        }
        return Math.min(best, leg.isEmpty() ? 0 : leg.size() - 1);
    }

    /** Read-only snapshot of the route state for diagnostics. */
    public RouteSnapshot snapshot() {
        List<List<BlockPos>> legsCopy = new ArrayList<>();
        for (List<BlockPos> leg : legs) legsCopy.add(Collections.unmodifiableList(new ArrayList<>(leg)));
        return new RouteSnapshot(Collections.unmodifiableList(legsCopy),
                legIndex, wpIndex, transit, exhausted, replanRequested, routedToX, routedToZ);
    }

    public static final class RouteSnapshot {
        public final List<List<BlockPos>> legs;
        public final int legIndex;
        public final int wpIndex;
        public final boolean transit;
        public final boolean exhausted;
        public final boolean replanRequested;
        public final int toX, toZ;

        RouteSnapshot(List<List<BlockPos>> legs, int legIndex, int wpIndex,
                      boolean transit, boolean exhausted, boolean replanRequested,
                      int toX, int toZ) {
            this.legs = legs;
            this.legIndex = legIndex;
            this.wpIndex = wpIndex;
            this.transit = transit;
            this.exhausted = exhausted;
            this.replanRequested = replanRequested;
            this.toX = toX;
            this.toZ = toZ;
        }
    }
}
