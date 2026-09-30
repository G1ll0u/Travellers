package com.jubitus.travellers.world.pathing.itinerary;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItineraryGatesTest {

    private static BlockPos p(int x, int z) {
        return new BlockPos(x, 64, z);
    }

    // Village at the origin, radius 60, gates east and north.
    private static ItineraryGates.Ring ring() {
        return new ItineraryGates.Ring(0, 0, 60, Arrays.asList(p(60, 0), p(0, -60)));
    }

    @Test
    void leavingPicksTheGateFacingTheDestinationAndThreadsItRadially() {
        List<BlockPos> via = ItineraryGates.exitVia(ring(), p(0, 0), p(1000, 100), 8, Collections.emptySet());
        assertEquals(Arrays.asList(p(52, 0), p(60, 0), p(68, 0)), via);
    }

    @Test
    void enteringIsTheReverseOrder() {
        List<BlockPos> via = ItineraryGates.enterVia(ring(), p(40, -1000), p(0, 0), 8, Collections.emptySet());
        assertEquals(Arrays.asList(p(0, -68), p(0, -60), p(0, -52)), via);
    }

    @Test
    void anExcludedGateIsNeverPicked() {
        Set<Long> excluded = new HashSet<>(Collections.singletonList(ItineraryGates.gateKey(p(60, 0))));
        List<BlockPos> via = ItineraryGates.exitVia(ring(), p(0, 0), p(1000, 100), 8, excluded);
        assertEquals(p(0, -60), via.get(1));
    }

    @Test
    void noGatesWhenTheLegStaysInsideOrOutside() {
        assertTrue(ItineraryGates.exitVia(ring(), p(0, 0), p(20, 20), 8, Collections.emptySet()).isEmpty());
        assertTrue(ItineraryGates.exitVia(ring(), p(500, 0), p(1000, 0), 8, Collections.emptySet()).isEmpty());
        assertTrue(ItineraryGates.exitVia(null, p(0, 0), p(1000, 0), 8, Collections.emptySet()).isEmpty());
    }

    @Test
    void assemblyLaysGateSegmentsAndBridgesOnlyBetweenTheOuterPoints() {
        List<BlockPos> exit = Arrays.asList(p(52, 0), p(60, 0), p(68, 0));
        List<BlockPos> enter = Arrays.asList(p(932, 0), p(940, 0), p(948, 0));
        ItineraryAssembly.Seed s = ItineraryAssembly.assemble(p(0, 0), exit, null, enter, p(1000, 0), 0, 0.0);
        Itinerary it = s.itinerary;
        assertEquals(8, it.size());
        byte[] expected = {Itinerary.WALK, Itinerary.GATE, Itinerary.GATE, Itinerary.WALK,
                Itinerary.GATE, Itinerary.GATE, Itinerary.WALK};
        for (int i = 0; i < expected.length; i++) assertEquals(expected[i], it.kind(i), "segment " + i);
        assertEquals(1, s.bridges.size());
        assertEquals(p(68, 0), s.bridges.get(0).start);
        assertEquals(p(932, 0), s.bridges.get(0).end);
    }
}
