package com.jubitus.travellers.entity.entityai.travel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TravelStuckLadderTest {

    // 3 / 6 / 10 / 15 s, pinned hop 8 s, underground 10 s, hop cooldown 10 s, edge after 30 s.
    private static final TravelStuckLadder.Timings T = new TravelStuckLadder.Timings(
            60, 120, 200, 300, 160, 200, 200, 40, 600, 20);

    private static final class Fired {
        final int tick;
        final TravelStuckLadder.Action action;

        Fired(int tick, TravelStuckLadder.Action action) {
            this.tick = tick;
            this.action = action;
        }
    }

    /** Runs a stall with no progress; pin/underground grow at the given rate (0 or 1 per tick). */
    private static List<Fired> stall(TravelStuckLadder l, int ticks, int pinRate, int undergroundRate) {
        List<Fired> out = new ArrayList<>();
        for (int i = 1; i <= ticks; i++) {
            TravelStuckLadder.Action a = l.tick(false, i * pinRate, i * undergroundRate, true, T);
            if (a != TravelStuckLadder.Action.NONE) out.add(new Fired(i, a));
        }
        return out;
    }

    @Test
    void rungsFireOnceEachInOrderThenHopsOnCooldown() {
        List<Fired> f = stall(new TravelStuckLadder(), 520, 0, 0);
        assertEquals(TravelStuckLadder.Action.REPATH, f.get(0).action);
        assertEquals(60, f.get(0).tick);
        assertEquals(TravelStuckLadder.Action.DETOUR, f.get(1).action);
        assertEquals(120, f.get(1).tick);
        assertEquals(TravelStuckLadder.Action.REPLAN, f.get(2).action);
        assertEquals(200, f.get(2).tick);
        assertEquals(TravelStuckLadder.Action.HOP, f.get(3).action);
        assertEquals(300, f.get(3).tick);
        assertEquals(TravelStuckLadder.Action.HOP, f.get(4).action);
        assertEquals(500, f.get(4).tick, "second hop only after the cooldown");
        assertEquals(5, f.size());
    }

    @Test
    void anyHeadwayResetsTheLadder() {
        TravelStuckLadder l = new TravelStuckLadder();
        stall(l, 130, 0, 0);
        assertEquals(2, l.rung());
        l.tick(true, 0, 0, true, T);
        assertEquals(0, l.rung());
        assertEquals(0, l.stallTicks());
        assertEquals(TravelStuckLadder.Action.REPATH, stall(l, 60, 0, 0).get(0).action);
    }

    @Test
    void aPinnedUnitHopsSoonerButOnlyAfterTheDetour() {
        List<Fired> f = stall(new TravelStuckLadder(), 200, 1, 0);
        assertEquals(TravelStuckLadder.Action.REPATH, f.get(0).action);
        assertEquals(TravelStuckLadder.Action.DETOUR, f.get(1).action);
        assertEquals(TravelStuckLadder.Action.HOP, f.get(2).action);
        assertEquals(160, f.get(2).tick);
    }

    @Test
    void undergroundGoesStraightToTheHop() {
        List<Fired> f = stall(new TravelStuckLadder(), 200, 0, 1);
        boolean hopAt200 = false;
        for (Fired x : f) if (x.action == TravelStuckLadder.Action.HOP && x.tick == 200) hopAt200 = true;
        assertTrue(hopAt200, "underground 10 s -> hop");
    }

    @Test
    void noHopOnTheTickTheMountStateFlipped() {
        TravelStuckLadder l = new TravelStuckLadder();
        stall(l, 299, 0, 0);
        assertEquals(TravelStuckLadder.Action.NONE, l.tick(false, 0, 0, false, T));
        assertEquals(TravelStuckLadder.Action.HOP, l.tick(false, 0, 0, true, T));
    }

    @Test
    void repeatedFailedHopsHandTheUnitToTheEdgeCheck() {
        TravelStuckLadder l = new TravelStuckLadder();
        stall(l, 300, 0, 0); // first hop fired
        l.hopFailed(T);
        l.hopFailed(T);
        l.hopFailed(T);
        assertEquals(TravelStuckLadder.Action.EDGE_VIRTUALIZE, l.tick(false, 0, 0, true, T));
    }

    @Test
    void edgeCheckIsThrottled() {
        List<Fired> f = stall(new TravelStuckLadder(), 700, 0, 0);
        int edges = 0;
        for (Fired x : f) if (x.action == TravelStuckLadder.Action.EDGE_VIRTUALIZE) edges++;
        assertEquals(6, edges, "edge from 600, then every 20 ticks");
    }
}
