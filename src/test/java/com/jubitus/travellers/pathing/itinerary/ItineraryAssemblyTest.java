package com.jubitus.travellers.pathing.itinerary;

import com.jubitus.travellers.pathing.route.TravelPlan;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItineraryAssemblyTest {

    private static BlockPos p(int x, int z) {
        return new BlockPos(x, 64, z);
    }

    private static List<BlockPos> leg(int... xz) {
        List<BlockPos> l = new ArrayList<>();
        for (int i = 0; i < xz.length; i += 2) l.add(p(xz[i], xz[i + 1]));
        return l;
    }

    @Test
    void noRoads_isOneStraightBridge() {
        ItineraryAssembly.Seed s = ItineraryAssembly.assemble(p(0, 0), null, p(1000, 0), 0, 2.0);
        assertEquals(2, s.itinerary.size());
        assertEquals(Itinerary.WALK, s.itinerary.kind(0));
        assertEquals(1, s.bridges.size());
        assertEquals("straight", s.itinerary.source);
    }

    @Test
    void roadLegsAreTaggedRoad_bridgesWalk() {
        ItineraryAssembly.Seed s = ItineraryAssembly.assemble(p(0, 0),
                Arrays.asList(leg(100, 0, 150, 0, 200, 0), leg(1000, 0, 1100, 0)), p(2000, 0), 0, 0.0);
        // origin, 100, 150, 200, 1000, 1100, dest
        assertEquals(7, s.itinerary.size());
        byte[] expected = {Itinerary.WALK, Itinerary.ROAD, Itinerary.ROAD, Itinerary.WALK, Itinerary.ROAD, Itinerary.WALK};
        for (int i = 0; i < expected.length; i++) assertEquals(expected[i], s.itinerary.kind(i), "segment " + i);
        assertEquals(3, s.bridges.size());
        assertEquals(p(200, 0), s.bridges.get(1).start);
        assertEquals(p(1000, 0), s.bridges.get(1).end);
    }

    @Test
    void originOnFirstLeg_noZeroLengthBridge() {
        ItineraryAssembly.Seed s = ItineraryAssembly.assemble(p(100, 0),
                Collections.singletonList(leg(100, 0, 200, 0)), p(500, 0), 0, 0.0);
        assertEquals(3, s.itinerary.size());
        assertEquals(1, s.bridges.size());
        assertEquals(p(200, 0), s.bridges.get(0).start);
    }

    @Test
    void longBridgeIsSplit() {
        ItineraryAssembly.Seed s = ItineraryAssembly.assemble(p(0, 0), null, p(2500, 0), 1000, 2.0);
        assertEquals(3, s.bridges.size());
        assertEquals(4, s.itinerary.size());
        assertEquals(p(2500, 0), s.bridges.get(2).end);
    }

    @Test
    void roadIsSimplifiedButKeepsItsShape() {
        List<BlockPos> road = new ArrayList<>();
        for (int x = 0; x <= 100; x += 4) road.add(p(x, 0));      // straight run
        for (int z = 4; z <= 100; z += 4) road.add(p(100, z));    // corner then north
        List<BlockPos> simple = ItineraryAssembly.simplify(road, 2.0);
        assertEquals(Arrays.asList(p(0, 0), p(100, 0), p(100, 100)), simple);
    }

    @Test
    void spliceTrimsACachedPolylineToTheBridgeEnds() {
        ItineraryAssembly.Seed s = ItineraryAssembly.assemble(p(0, 0), null, p(300, 0), 0, 0.0);
        // A cached plan from a neighbouring origin: starts 40 blocks before our bridge start.
        List<BlockPos> poly = leg(-40, 0, 0, 2, 100, 60, 200, 60, 300, 1);
        Itinerary out = s.itinerary.withSplice(p(0, 0), p(300, 0), poly, TravelPlan.QUALITY_COARSE);
        assertEquals(4, out.size());
        assertEquals(0, out.x(0));
        assertEquals(100, out.x(1));
        assertEquals(200, out.x(2));
        assertEquals(300, out.x(3));
        assertEquals(0, out.z(3));
        assertEquals(TravelPlan.QUALITY_COARSE, out.quality);
    }

    @Test
    void spliceKeepsRoadSegmentsAroundTheBridge() {
        ItineraryAssembly.Seed s = ItineraryAssembly.assemble(p(0, 0),
                Collections.singletonList(leg(100, 0, 200, 0)), p(500, 0), 0, 0.0);
        Itinerary out = s.itinerary.withSplice(p(200, 0), p(500, 0), leg(200, 0, 350, 50, 500, 0),
                TravelPlan.QUALITY_COARSE);
        // 0 -WALK- 100 -ROAD- 200 -WALK- 350 -WALK- 500
        assertEquals(5, out.size());
        assertEquals(Itinerary.WALK, out.kind(0));
        assertEquals(Itinerary.ROAD, out.kind(1));
        assertEquals(Itinerary.WALK, out.kind(2));
        assertEquals(Itinerary.WALK, out.kind(3));
    }

    @Test
    void spliceMissesWhenAnchorsAreGone() {
        ItineraryAssembly.Seed s = ItineraryAssembly.assemble(p(0, 0), null, p(300, 0), 0, 0.0);
        assertNull(s.itinerary.withSplice(p(0, 0), p(299, 0), leg(0, 0, 150, 9, 299, 0), TravelPlan.QUALITY_COARSE));
    }

    @Test
    void roadDetourRatio_acceptsAModestRoad_rejectsAFarBehindOne() {
        // Straight 1000. A road running alongside, joined 20 blocks off: well under 1.5.
        double near = ItineraryPlanner.roadDetourRatio(p(0, 0),
                Collections.singletonList(leg(0, 20, 1000, 20)), p(1000, 0), 1.5);
        // A road joined 400 blocks behind the origin: walking back to it costs more than it saves.
        double behind = ItineraryPlanner.roadDetourRatio(p(0, 0),
                Collections.singletonList(leg(-400, 0, 1000, 0)), p(1000, 0), 1.5);
        assertTrue(near < 1.5, "near road ratio " + near);
        assertTrue(behind > 1.5, "far-behind road ratio " + behind);
    }
}
