package com.jubitus.travellers.worldmap;

import com.jubitus.travellers.worldmap.gui.WorkerMarkerSmoother;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerMarkerSmootherTest {

    private static final long D = WorkerMarkerSmoother.RENDER_DELAY_MS;
    private final UUID id = UUID.randomUUID();

    @Test
    void firstSampleIsShownAtOnce() {
        WorkerMarkerSmoother s = new WorkerMarkerSmoother();
        s.observe(id, 10, 20, 0);
        assertEquals(10f, s.x(id, 0, 0));
        assertEquals(20f, s.z(id, 0, 500));
    }

    @Test
    void passesThroughSamplesOneDelayLate() {
        WorkerMarkerSmoother s = new WorkerMarkerSmoother();
        s.observe(id, 0, 0, 0);
        s.observe(id, 4, 0, 1000);
        s.observe(id, 8, 0, 2000);
        assertEquals(0f, s.x(id, -1, D), 1e-3);
        assertEquals(4f, s.x(id, -1, 1000 + D), 1e-3);
        assertEquals(8f, s.x(id, -1, 2000 + D), 1e-3);
        assertEquals(6f, s.x(id, -1, 1500 + D), 1e-3); // constant speed stays straight
    }

    @Test
    void speedHasNoJumpAtASample() {
        WorkerMarkerSmoother s = new WorkerMarkerSmoother();
        s.observe(id, 0, 0, 0);
        s.observe(id, 10, 0, 1000);
        s.observe(id, 12, 0, 2000); // slows down
        s.observe(id, 14, 0, 3000);
        float before = s.x(id, 0, 2000 + D) - s.x(id, 0, 2000 + D - 10);
        float after = s.x(id, 0, 2000 + D + 10) - s.x(id, 0, 2000 + D);
        assertTrue(Math.abs(before - after) < 0.02f, "speed continuous across the sample: " + before + " vs " + after);
    }

    @Test
    void lateUpdateKeepsMovingBrieflyThenStops() {
        WorkerMarkerSmoother s = new WorkerMarkerSmoother();
        s.observe(id, 0, 0, 0);
        s.observe(id, 4, 0, 1000);
        assertEquals(6f, s.x(id, -1, 1000 + D + 500), 1e-3);
        assertEquals(6f, s.x(id, -1, 1000 + D + 5000), 1e-3);
    }

    @Test
    void hugeJumpSnaps() {
        WorkerMarkerSmoother s = new WorkerMarkerSmoother();
        s.observe(id, 0, 0, 0);
        s.observe(id, 500, 0, 1000);
        assertEquals(500f, s.x(id, -1, 1000 + D), 1e-4);
        assertEquals(500f, s.x(id, -1, 1000), 1e-4);
    }

    @Test
    void unknownWorkerUsesFallbackAndRetainDropsTracks() {
        WorkerMarkerSmoother s = new WorkerMarkerSmoother();
        s.observe(id, 3, 3, 0);
        s.retainOnly(Collections.emptySet());
        assertEquals(7f, s.x(id, 7, 0));
    }
}
