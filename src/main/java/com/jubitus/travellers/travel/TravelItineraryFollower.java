package com.jubitus.travellers.travel;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.pathing.itinerary.Itinerary;
import com.jubitus.travellers.pathing.itinerary.ItineraryPlanner;
import com.jubitus.travellers.pathing.itinerary.ItineraryState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.WorldServer;
import javax.annotation.Nullable;

/** The itinerary a loaded unit follows for its leg: build, keep, replan, carrot, gates. */
final class TravelItineraryFollower {

    private final EntityAITravel ai;
    private final EntityTraveller mob;

    int nextItineraryReplanTick = 0;
    private static final int ITINERARY_REPLAN_COOLDOWN_TICKS = 10 * 20;
    static final int ITINERARY_BACKLOG_CHECK_TICKS = 40;

    TravelItineraryFollower(EntityAITravel ai, EntityTraveller mob) {
        this.ai = ai;
        this.mob = mob;
    }

    /**
     * Makes sure an itinerary-driven unit holds an itinerary for {@code targetVillage}. One handed
     * over from the virtual record (same leg) is kept as-is, cursor included; anything else is built
     * fresh through the same {@link ItineraryPlanner} the offscreen simulation uses.
     */
    void ensureItinerary(boolean traceKept) {
        if (!(mob.world instanceof WorldServer) || ai.targetVillage == null) return;
        if (!mob.isItineraryDriven()) return;
        ItineraryState st = mob.getItineraryState();
        if (st.isFor(ai.targetVillage)) {
            Itinerary it = st.get();
            st.cursor.advance(it, mob.posX, mob.posZ);
            if (traceKept) com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "path",
                    String.format(java.util.Locale.ROOT, "itinerary kept src=%s verts=%d seg=%d along=%.0f/%.0f off=%.1f",
                            it.source, it.size(), st.cursor.segment(), st.cursor.along(), it.totalLength(),
                            st.cursor.offRouteDistance()));
            return;
        }
        if (st.isForced()) return;
        buildItinerary();
    }

    private void buildItinerary() {
        WorldServer ws = (WorldServer) mob.world;
        ItineraryPlanner.build(ws, mob.getUniqueID(), mob.getItineraryState(), mob.posX, mob.posY, mob.posZ,
                ai.targetVillage,
                ItineraryPlanner.mayUseRoads(mob instanceof com.jubitus.travellers.unit.EntityTravellerRaider),
                com.jubitus.travellers.pathing.PathBody.isMounted(mob), mob.getCultureId(),
                ws.getTotalWorldTime(), mob);
    }

    /** Retries terrain plans for the itinerary's still-straight bridges (budgeted inside). */
    void tickItineraryBacklog() {
        ItineraryState st = mob.getItineraryState();
        if (!st.hasBacklog() || !st.isFor(ai.targetVillage)) return;
        ItineraryPlanner.tryPlanBacklog((WorldServer) mob.world, mob.getUniqueID(), st,
                com.jubitus.travellers.pathing.PathBody.isMounted(mob), mob.getCultureId(),
                mob.world.getTotalWorldTime());
    }

    /** Rebuilds the itinerary from the current position (pushed far off it, or crossed water off it). */
    void replanItineraryFromHere(String reason) {
        if (!mob.isItineraryDriven() || mob.getItineraryState().isForced()) return;
        if (mob.ticksExisted < nextItineraryReplanTick) return;
        nextItineraryReplanTick = mob.ticksExisted + ITINERARY_REPLAN_COOLDOWN_TICKS;
        com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "path", "itinerary replan: " + reason);
        buildItinerary();
    }

    /** Remaining distance along the itinerary (or straight to the target), in blocks. */
    double progressDist() {
        double d2 = progressDistSq();
        return d2 == Double.MAX_VALUE ? Double.MAX_VALUE : Math.sqrt(d2);
    }

    /**
     * Progress measure for the stuck ladder: remaining distance along the itinerary when the unit
     * follows one for this leg, else straight XZ distance to the target. Squared.
     */
    private double progressDistSq() {
        if (ai.targetVillage == null) return Double.MAX_VALUE;
        ItineraryState st = mob.getItineraryState();
        if (mob.isItineraryDriven() && st.isFor(ai.targetVillage)) {
            st.cursor.advance(st.get(), mob.posX, mob.posZ);
            double rem = st.cursor.remaining(st.get());
            return rem * rem;
        }
        return TravelTerrain.horizDistSq(mob.getPosition(), ai.targetVillage);
    }

    /**
     * The itinerary's carrot resolved to ground: a point {@code step} blocks ahead of the unit's
     * projection onto the polyline (forward-only cursor), halved until it lands on navigable ground.
     */
    @Nullable
    BlockPos itineraryWaypoint(BlockPos dest, int step) {
        if (!mob.isItineraryDriven()) return null;
        ItineraryState st = mob.getItineraryState();
        if (!st.isFor(dest)) return null;
        Itinerary it = st.get();
        st.cursor.advance(it, mob.posX, mob.posZ);
        if (st.cursor.offRouteDistance() > TravellersConfig.TRAVEL.plannedRouteMaxOffRouteBlocks) {
            replanItineraryFromHere(String.format(java.util.Locale.ROOT, "off route %.0f blocks",
                    st.cursor.offRouteDistance()));
            it = st.get();
            if (it == null || !st.isFor(dest)) return null;
        }
        int carrot = Math.min(TravellersConfig.ROUTING.itineraryCarrotBlocks, step);
        // Near a wall gate, aim short so the unit threads the opening instead of cutting the corner.
        if (st.cursor.distanceToKind(it, Itinerary.GATE, carrot) >= 0.0) {
            carrot = Math.min(carrot, Math.max(8, TravellersConfig.ROUTING.gateApproachBlocks));
        }
        for (int s = carrot; s >= 8; s /= 2) {
            st.cursor.computeCarrot(it, s);
            // On a road the itinerary knows the surface height (a bridge deck, not the gap under it);
            // elsewhere the layer nearest our own height is the best guess.
            int refY = st.cursor.carrotKind == Itinerary.ROAD
                    ? MathHelper.floor(st.cursor.carrotY) + 1 : MathHelper.floor(mob.posY); // road Y is the surface block
            BlockPos ground = ai.terrain.findGroundNear(new BlockPos(MathHelper.floor(st.cursor.carrotX),
                    refY, MathHelper.floor(st.cursor.carrotZ)), refY);
            if (ground != null && ai.terrain.isNavigable(ground)) return ground;
        }
        return null;
    }

    /** The wall gate the itinerary threads here (cursor on its GATE run or one carrot before it), else null. */
    @Nullable
    private BlockPos gateAhead() {
        if (ai.targetVillage == null || !mob.isItineraryDriven()) return null;
        ItineraryState st = mob.getItineraryState();
        if (!st.isFor(ai.targetVillage)) return null;
        Itinerary it = st.get();
        st.cursor.advance(it, mob.posX, mob.posZ);
        if (st.cursor.distanceToKind(it, Itinerary.GATE, TravellersConfig.ROUTING.itineraryCarrotBlocks) < 0.0) return null;
        int last = it.size() - 2;
        int k = st.cursor.segment();
        while (k < last && it.kind(k) != Itinerary.GATE) k++;
        // A GATE run is two segments with the gate between them.
        return k < last && it.kind(k + 1) == Itinerary.GATE ? it.vertex(k + 1) : it.vertex(k);
    }

    /** Stuck at a wall gate: rule it out for this leg and rebuild, so another gate is tried. */
    boolean abandonUnreachableGate() {
        BlockPos gate = gateAhead();
        if (gate == null || mob.getItineraryState().isForced()) return false;
        if (!mob.getItineraryState().excludedGates.add(
                com.jubitus.travellers.pathing.itinerary.ItineraryGates.gateKey(gate))) return false;
        com.jubitus.travellers.debug.trace.TravellerTracer.incident(mob, "stuck",
                "gate unreachable " + gate.getX() + "," + gate.getZ() + ", rebuilding the itinerary");
        nextItineraryReplanTick = 0;
        replanItineraryFromHere("gate unreachable");
        ai.resetStuckState();
        ai.currentWaypoint = null;
        mob.getTravelCommon().setCurrentWaypoint(null);
        ai.paths.clearNav();
        return true;
    }
}
