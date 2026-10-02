package com.jubitus.travellers.pathing.itinerary;

import com.jubitus.travellers.pathing.route.TravelPlan;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItineraryCursorTest {

    private static BlockPos p(int x, int z) {
        return new BlockPos(x, 64, z);
    }

    // L-shape: east 100, then north 100. Vertex coords are block corners; the cursor works on centres.
    private static Itinerary lShape() {
        return Itinerary.of(Arrays.asList(p(0, 0), p(100, 0), p(100, 100)), Itinerary.WALK,
                TravelPlan.QUALITY_STRAIGHT, "test");
    }

    @Test
    void resetProjectsOntoNearestSegment() {
        ItineraryCursor c = new ItineraryCursor();
        Itinerary it = lShape();
        c.reset(it, 40.5, 10.5);
        assertEquals(0, c.segment());
        assertEquals(40.0, c.along(), 1e-6);
        assertEquals(10.0, c.offRouteDistance(), 1e-6);
    }

    @Test
    void advanceNeverMovesBackwards() {
        ItineraryCursor c = new ItineraryCursor();
        Itinerary it = lShape();
        c.reset(it, 80.5, 0.5);
        c.advance(it, 20.5, 0.5); // pushed back 60 blocks
        assertEquals(80.0, c.along(), 1e-6);
        assertEquals(60.0, c.offRouteDistance(), 1e-6);
    }

    @Test
    void advanceCrossesVertices() {
        ItineraryCursor c = new ItineraryCursor();
        Itinerary it = lShape();
        c.reset(it, 0.5, 0.5);
        c.advance(it, 100.5, 30.5);
        assertEquals(1, c.segment());
        assertEquals(130.0, c.along(), 1e-6);
    }

    @Test
    void advanceIgnoresFarAheadPartOfAHairpin() {
        // Out 100 east and straight back: standing at x=10 must not jump to the return leg's end.
        Itinerary it = Itinerary.of(Arrays.asList(p(0, 0), p(100, 0), p(100, 4), p(0, 4)), Itinerary.WALK,
                TravelPlan.QUALITY_STRAIGHT, "test");
        ItineraryCursor c = new ItineraryCursor();
        c.reset(it, 0.5, 0.5);
        c.advance(it, 10.5, 1.5);
        assertEquals(0, c.segment());
        assertTrue(c.along() < 20.0);
    }

    @Test
    void carrotFollowsThePolylineAcrossAVertex() {
        ItineraryCursor c = new ItineraryCursor();
        Itinerary it = lShape();
        c.reset(it, 90.5, 0.5);
        c.computeCarrot(it, 30.0);
        assertEquals(100.5, c.carrotX, 1e-6);
        assertEquals(20.5, c.carrotZ, 1e-6);
    }

    @Test
    void carrotClampsAtTheEnd() {
        ItineraryCursor c = new ItineraryCursor();
        Itinerary it = lShape();
        c.reset(it, 100.5, 90.5);
        c.computeCarrot(it, 50.0);
        assertEquals(100.5, c.carrotX, 1e-6);
        assertEquals(100.5, c.carrotZ, 1e-6);
        assertEquals(10.0, c.remaining(it), 1e-6);
    }

    @Test
    void steppingByCarrotStaysOnTheLine() {
        // What the virtual simulation does every sim step.
        ItineraryCursor c = new ItineraryCursor();
        Itinerary it = lShape();
        double x = 0.5, z = 0.5;
        c.reset(it, x, z);
        for (int i = 0; i < 100 && c.remaining(it) > 0.01; i++) {
            c.advance(it, x, z);
            c.computeCarrot(it, 7.0);
            x = c.carrotX;
            z = c.carrotZ;
            assertTrue(c.offRouteDistance() < 1e-6);
        }
        assertEquals(100.5, x, 1e-6);
        assertEquals(100.5, z, 1e-6);
    }

    @Test
    void stateNbtRoundTripKeepsItineraryCursorAndBridges() {
        ItineraryAssembly.Seed seed = ItineraryAssembly.assemble(p(0, 0),
                Arrays.asList(Arrays.asList(p(50, 0), p(60, 0), p(70, 0))), p(200, 0), 0, 0.0);
        ItineraryState st = new ItineraryState();
        st.set(seed.itinerary, 65.5, 0.5, seed.bridges, 123L);

        NBTTagCompound tag = new NBTTagCompound();
        st.writeNbt(tag, "Itin");
        ItineraryState back = new ItineraryState();
        back.readNbt(tag, "Itin");

        Itinerary a = st.get(), b = back.get();
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.x(i), b.x(i));
            assertEquals(a.z(i), b.z(i));
            if (i < a.size() - 1) assertEquals(a.kind(i), b.kind(i));
        }
        assertEquals(st.cursor.along(), back.cursor.along(), 1e-9);
        assertEquals(st.cursor.segment(), back.cursor.segment());
        assertTrue(back.hasBacklog());
        assertEquals(123L, back.planTick);
        assertTrue(back.isFor(p(210, 5)));
    }

    @Test
    void distanceToKindFindsABoatSegmentAheadOnly() {
        // WALK 0..100, BOAT 100..300, WALK 300..400 along x.
        Itinerary it = new Itinerary(new int[]{0, 100, 300, 400}, new int[]{64, 64, 64, 64},
                new int[]{0, 0, 0, 0}, new byte[]{Itinerary.WALK, Itinerary.BOAT, Itinerary.WALK},
                TravelPlan.QUALITY_COARSE, "test");
        ItineraryCursor c = new ItineraryCursor();
        c.reset(it, 50.5, 0.5);
        assertEquals(-1.0, c.distanceToKind(it, Itinerary.BOAT, 24.0), 1e-6, "boat too far ahead");
        c.advance(it, 80.5, 0.5);
        assertEquals(20.0, c.distanceToKind(it, Itinerary.BOAT, 24.0), 1e-6);
        c.advance(it, 150.5, 0.5);
        assertEquals(Itinerary.BOAT, c.kind(it));
        assertEquals(0.0, c.distanceToKind(it, Itinerary.BOAT, 24.0), 1e-6);
        c.advance(it, 350.5, 0.5);
        assertEquals(-1.0, c.distanceToKind(it, Itinerary.BOAT, 24.0), 1e-6, "boat is behind");
    }
}
