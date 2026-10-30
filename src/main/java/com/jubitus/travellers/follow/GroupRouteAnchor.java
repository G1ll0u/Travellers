package com.jubitus.travellers.follow;

import com.jubitus.travellers.debug.trace.TravellerTracer;
import com.jubitus.travellers.pathing.itinerary.Itinerary;
import com.jubitus.travellers.pathing.itinerary.ItineraryState;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.util.math.BlockPos;

import java.util.Locale;

/**
 * Where a travelling group stands, read from its leader's route rather than its feet. While the leader walks
 * its itinerary the formation faces along the route; when the leader falls or strays off the route the anchor
 * freezes at its last good spot and the group waits there until the leader is back. One per group leader.
 */
final class GroupRouteAnchor {

    /** A fall this long means the leader left the walkable route (vanilla damage starts at 3). */
    private static final float FALL_FREEZE = 3.0F;
    /** Lower than the last good spot by this much, steeper than 45 degrees: a fall nobody watched happen. */
    private static final double DROP_FREEZE = 2.5D;
    private static final double OFF_ROUTE_FREEZE = 5.0D;
    /** Back on the route within this of the anchor, at its height: the group moves on. */
    private static final double RETURN_RADIUS = 4.0D;
    private static final double RETURN_HEIGHT = 1.5D;
    /** Back on the route this far from the anchor (a replanned leg, a legit drop): the group follows. */
    private static final double RELEASE_DISTANCE = 10.0D;
    private static final long MAX_FREEZE_TICKS = 1200L;
    /** The route direction is taken toward the point this far ahead, so corners turn the group gradually. */
    private static final double HEADING_LOOKAHEAD = 6.0D;
    /** Segments scanned around the leader's cursor when projecting it onto the route. */
    private static final int PROJECT_SPAN = 6;

    private long lastUpdateTick = Long.MIN_VALUE;
    private boolean onRoute;
    private boolean frozen;
    private long frozenSince;
    private boolean hasGood;
    private double goodX, goodY, goodZ;
    private double along, offRoute;
    private int seg;
    private Itinerary current;
    private Itinerary goodIt;
    private double goodAlong;

    boolean isFrozen() {
        return frozen;
    }

    double x() {
        return goodX;
    }

    double y() {
        return goodY;
    }

    double z() {
        return goodZ;
    }

    /** Once per tick per leader; cheap (a few segment projections, no block reads). */
    void update(EntityTraveller leader, long now) {
        if (lastUpdateTick == now) return;
        lastUpdateTick = now;

        Itinerary it = walkedItinerary(leader);
        onRoute = it != null;
        current = it;
        if (!onRoute) {
            if (frozen) release(leader, "leader stopped travelling");
            hasGood = false;
            return;
        }
        Entity body = leader.getLowestRidingEntity();
        project(it, leader.getItineraryState().cursor.segment(), leader.posX, leader.posZ);

        if (!frozen) {
            boolean fell = body.fallDistance > FALL_FREEZE || (hasGood && droppedSteeply(leader));
            if ((fell || offRoute > OFF_ROUTE_FREEZE) && hasGood) {
                frozen = true;
                frozenSince = now;
                TravellerTracer.event(leader, "follow", String.format(Locale.ROOT,
                        "group anchor frozen (%s) at %.1f %.1f %.1f", fell ? "fell" : "off route " + fmt(offRoute),
                        goodX, goodY, goodZ));
            } else if (body.onGround && !fell) {
                hasGood = true;
                goodX = leader.posX;
                goodY = leader.posY;
                goodZ = leader.posZ;
                goodIt = it;
                goodAlong = along;
            }
            return;
        }

        double dx = leader.posX - goodX, dz = leader.posZ - goodZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        boolean steady = body.onGround && offRoute <= OFF_ROUTE_FREEZE;
        if (steady && horizontal <= RETURN_RADIUS && Math.abs(leader.posY - goodY) <= RETURN_HEIGHT) {
            release(leader, "leader back at the anchor");
        } else if (steady && horizontal >= RELEASE_DISTANCE) {
            release(leader, "leader moved on " + fmt(horizontal) + " blocks away");
        } else if (now - frozenSince >= MAX_FREEZE_TICKS) {
            release(leader, "waited too long");
        }
    }

    /** The route the group lines up on (the one it waits on while frozen); null when not walking one. */
    Itinerary baseItinerary() {
        return frozen ? goodIt : current;
    }

    /** Distance along {@link #baseItinerary} the group lines up behind. */
    double baseAlong() {
        return frozen ? goodAlong : along;
    }

    /**
     * The route direction just ahead of the leader into {@code out} (unit XZ); false when the leader is not
     * walking a route or the route ahead is degenerate.
     */
    boolean routeHeading(EntityTraveller leader, double[] out) {
        Itinerary it = onRoute ? leader.getItineraryState().get() : null;
        if (it == null) return false;
        double ax = pointX(it, along), az = pointZ(it, along);
        double ahead = Math.min(it.totalLength(), along + HEADING_LOOKAHEAD);
        double hx = pointX(it, ahead) - ax, hz = pointZ(it, ahead) - az;
        double len = Math.sqrt(hx * hx + hz * hz);
        if (len < 0.5D) return false;
        out[0] = hx / len;
        out[1] = hz / len;
        return true;
    }

    private boolean droppedSteeply(EntityTraveller leader) {
        double drop = goodY - leader.posY;
        double dx = leader.posX - goodX, dz = leader.posZ - goodZ;
        return drop > DROP_FREEZE && drop * drop > dx * dx + dz * dz;
    }

    private void release(EntityTraveller leader, String why) {
        frozen = false;
        hasGood = false;
        TravellerTracer.event(leader, "follow", "group anchor released: " + why);
    }

    /** The leader's itinerary while it is actually walking it toward its target; null otherwise. */
    static Itinerary walkedItinerary(EntityTraveller leader) {
        if (!leader.isItineraryDriven() || leader.isOnStay() || leader.getAttackTarget() != null) return null;
        if (leader.getTravelCommon().isIdlingAtStop()) return null;
        if (leader.getLowestRidingEntity() instanceof EntityBoat) return null;
        BlockPos target = leader.getTravelCommon().getTargetVillage();
        ItineraryState st = leader.getItineraryState();
        Itinerary it = st.get();
        return it != null && it.size() >= 2 && st.isFor(target) ? it : null;
    }

    // Own projection: the leader's cursor is the travel AI's, advanced on its own schedule.
    private void project(Itinerary it, int aroundSeg, double px, double pz) {
        int last = it.size() - 2;
        int from = Math.max(0, Math.min(aroundSeg, last) - PROJECT_SPAN);
        int to = Math.min(last, aroundSeg + PROJECT_SPAN);
        double bestD = Double.MAX_VALUE;
        for (int k = from; k <= to; k++) {
            double ax = it.x(k) + 0.5D, az = it.z(k) + 0.5D;
            double sx = it.x(k + 1) + 0.5D - ax, sz = it.z(k + 1) + 0.5D - az;
            double len2 = sx * sx + sz * sz;
            double t = len2 < 1e-9 ? 0.0D : Math.max(0.0D, Math.min(1.0D, ((px - ax) * sx + (pz - az) * sz) / len2));
            double qx = ax + sx * t, qz = az + sz * t;
            double d = (px - qx) * (px - qx) + (pz - qz) * (pz - qz);
            if (d < bestD) {
                bestD = d;
                seg = k;
                along = it.cum(k) + (it.cum(k + 1) - it.cum(k)) * t;
            }
        }
        offRoute = Math.sqrt(bestD);
    }

    private int segmentAt(Itinerary it, double a) {
        int last = it.size() - 2;
        int k = Math.max(0, Math.min(seg, last));
        while (k > 0 && it.cum(k) > a) k--;
        while (k < last && it.cum(k + 1) <= a) k++;
        return k;
    }

    private double pointX(Itinerary it, double a) {
        int k = segmentAt(it, a);
        return it.x(k) + 0.5D + (it.x(k + 1) - it.x(k)) * fraction(it, k, a);
    }

    private double pointZ(Itinerary it, double a) {
        int k = segmentAt(it, a);
        return it.z(k) + 0.5D + (it.z(k + 1) - it.z(k)) * fraction(it, k, a);
    }

    private static double fraction(Itinerary it, int k, double a) {
        double len = it.cum(k + 1) - it.cum(k);
        return len < 1e-9 ? 1.0D : Math.max(0.0D, Math.min(1.0D, (a - it.cum(k)) / len));
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
