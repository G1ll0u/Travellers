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

    @Test
    void jitteredArrivalsKeepTheRealSpeed() {
        WorkerMarkerSmoother s = new WorkerMarkerSmoother();
        // 4 blocks every 20 ticks; the third update arrives late, the fourth on time.
        long[] arrivals = {0, 1000, 1950, 2000, 3000};
        for (int i = 0; i < arrivals.length; i++) {
            long t = s.sampleTime(i * 20L, arrivals[i]);
            s.observe(id, i * 4, 0, t);
        }
        float maxStep = 0f;
        for (long at = D; at < 3000 + D; at += 10) {
            maxStep = Math.max(maxStep, Math.abs(s.x(id, 0, at + 10) - s.x(id, 0, at)));
        }
        // 4 blocks/s = 0.04 per 10 ms; arrival-time stamping would show 4 blocks in 50 ms (0.8 per 10 ms).
        assertTrue(maxStep < 0.08f, "drawn speed stays near the real one: " + maxStep);
    }

    @Test
    void sampleTimeStaysNearArrival() {
        WorkerMarkerSmoother s = new WorkerMarkerSmoother();
        assertEquals(0L, s.sampleTime(0, 0));
        assertEquals(1000L, s.sampleTime(20, 1000));
        assertEquals(2000L, s.sampleTime(40, 2300)); // late packet: game time wins
        assertEquals(3600L, s.sampleTime(60, 4000)); // long stall: never dated far before arrival
        assertEquals(4500L, s.sampleTime(80, 4100)); // early packet: ahead by game time, the lead shrinking slowly
        assertEquals(9000L, s.sampleTime(5, 9000));  // tick counter went back: restart from arrival
    }
}
