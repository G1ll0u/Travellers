package com.jubitus.travellers.worldmap;

import com.jubitus.travellers.worldmap.gui.WorkerMarkerSmoother;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkerMarkerSmootherTest {

    private final UUID id = UUID.randomUUID();

    @Test
    void firstSampleIsShownAtOnce() {
        WorkerMarkerSmoother s = new WorkerMarkerSmoother();
        s.observe(id, 10, 20, 0);
        assertEquals(10f, s.x(id, 0, 0));
        assertEquals(20f, s.z(id, 0, 500));
    }

    @Test
    void glidesTowardNewSampleOverTheLastInterval() {
        WorkerMarkerSmoother s = new WorkerMarkerSmoother();
        s.observe(id, 0, 0, 0);
        s.observe(id, 4, 0, 1000);
        assertEquals(0f, s.x(id, -1, 1000), 1e-4);
        assertEquals(2f, s.x(id, -1, 1500), 1e-4);
        assertEquals(4f, s.x(id, -1, 2000), 1e-4);
        assertEquals(4f, s.x(id, -1, 5000), 1e-4);
    }

    @Test
    void newSampleMidGlideStartsFromWhereItIsDrawn() {
        WorkerMarkerSmoother s = new WorkerMarkerSmoother();
        s.observe(id, 0, 0, 0);
        s.observe(id, 4, 0, 1000);
        s.observe(id, 8, 0, 1500); // drawn at 2 by now
        assertEquals(2f, s.x(id, -1, 1500), 1e-4);
    }

    @Test
    void hugeJumpSnaps() {
        WorkerMarkerSmoother s = new WorkerMarkerSmoother();
        s.observe(id, 0, 0, 0);
        s.observe(id, 500, 0, 1000);
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
