package com.jubitus.travellers.entity.entityai.travel;

/**
 * The single stuck ladder of the travel AI. One progress measure (remaining distance along the
 * itinerary, judged by the caller) drives it; a raw "not moving at all" guard only lets the hop come
 * sooner. Each rung fires once, in order, then the next one after more stalled time; any progress
 * resets it. Rungs therefore cannot starve each other. Pure state, no world access.
 */
public final class TravelStuckLadder {

    public enum Action { NONE, REPATH, DETOUR, REPLAN, HOP, EDGE_VIRTUALIZE }

    /** Thresholds in ticks. */
    public static final class Timings {
        final int repath, detour, replan, hop, hardPinHop, undergroundHop, hopCooldown, failedHopCooldown,
                edgeVirtualize, edgeRetry;

        public Timings(int repath, int detour, int replan, int hop, int hardPinHop, int undergroundHop,
                       int hopCooldown, int failedHopCooldown, int edgeVirtualize, int edgeRetry) {
            this.repath = repath;
            this.detour = detour;
            this.replan = replan;
            this.hop = hop;
            this.hardPinHop = hardPinHop;
            this.undergroundHop = undergroundHop;
            this.hopCooldown = hopCooldown;
            this.failedHopCooldown = failedHopCooldown;
            this.edgeVirtualize = edgeVirtualize;
            this.edgeRetry = edgeRetry;
        }
    }

    // Failed hops in a row before handing the unit to the offscreen simulation (edge only).
    static final int FAILED_HOPS_BEFORE_EDGE = 3;

    private int stall;
    private int rung;
    private int hopCooldown;
    private int failedHops;
    private int edgeCooldown;

    public int stallTicks() {
        return stall;
    }

    public int failedHops() {
        return failedHops;
    }

    public int rung() {
        return rung;
    }

    public void reset() {
        stall = 0;
        rung = 0;
        hopCooldown = 0;
        failedHops = 0;
        edgeCooldown = 0;
    }

    /**
     * One travel tick.
     *
     * @param progressed  the remaining distance improved enough this tick
     * @param pinTicks    how long the unit has not moved at all
     * @param underground how long the unit has been underground
     * @param hopAllowed  false on a tick where a hop would be wrong (mount state just flipped)
     */
    public Action tick(boolean progressed, int pinTicks, int underground, boolean hopAllowed, Timings t) {
        if (progressed) {
            reset();
            return Action.NONE;
        }
        stall++;
        if (hopCooldown > 0) hopCooldown--;
        if (edgeCooldown > 0) edgeCooldown--;

        if (edgeCooldown == 0 && (stall >= t.edgeVirtualize || failedHops >= FAILED_HOPS_BEFORE_EDGE)) {
            edgeCooldown = t.edgeRetry;
            return Action.EDGE_VIRTUALIZE;
        }

        boolean hopDue = (rung >= 3 && stall >= t.hop)
                || (rung >= 2 && pinTicks >= t.hardPinHop)
                || underground >= t.undergroundHop;
        if (hopDue && hopAllowed && hopCooldown == 0) {
            rung = 4;
            hopCooldown = t.hopCooldown;
            return Action.HOP;
        }
        if (rung == 0 && stall >= t.repath) {
            rung = 1;
            return Action.REPATH;
        }
        if (rung == 1 && stall >= t.detour) {
            rung = 2;
            return Action.DETOUR;
        }
        if (rung == 2 && stall >= t.replan) {
            rung = 3;
            return Action.REPLAN;
        }
        return Action.NONE;
    }

    /** Evidence gathered elsewhere (the follow AI): the rungs still fire one by one, in order. */
    public void preload(int stallTicks) {
        if (stall < stallTicks) stall = stallTicks;
    }

    /** A* proved no way out from here (detour and local escape both unreachable): hop on the next tick. */
    public void deadEnd(Timings t) {
        rung = 3;
        stall = Math.max(stall, t.hop);
        hopCooldown = 0;
    }

    /** The hop could not be checked this tick (world-wide budget spent): retry next tick, not a failure. */
    public void hopDeferred() {
        hopCooldown = 1;
    }

    /** The hop found nowhere to land: try again soon, and count toward the edge hand-off. */
    public void hopFailed(Timings t) {
        failedHops++;
        hopCooldown = t.failedHopCooldown;
    }
}
