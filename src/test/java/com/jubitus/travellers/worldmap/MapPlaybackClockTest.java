package com.jubitus.travellers.worldmap;

import com.jubitus.travellers.worldmap.gui.MapPlaybackClock;
import com.jubitus.travellers.worldmap.gui.WorkerMarkerSmoother;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapPlaybackClockTest {

    private static final long FRAME_MS = 16L;
    private static final float BLOCKS_PER_UPDATE = 4f;

    /** Per-frame marker steps once settled: {min, max, mean} in blocks. */
    private static float[] replay(double tps, double jitterMs, int updates) {
        MapPlaybackClock clock = new MapPlaybackClock();
        WorkerMarkerSmoother s = new WorkerMarkerSmoother();
        UUID id = UUID.randomUUID();
        Random r = new Random(7);
        double wallPerUpdate = 20 * 1000.0 / tps;
        long[] arrival = new long[updates];
        for (int i = 0; i < updates; i++) {
            arrival[i] = 10_000 + Math.round(i * wallPerUpdate + Math.abs(r.nextGaussian()) * jitterMs);
            if (i > 0) arrival[i] = Math.max(arrival[i], arrival[i - 1]);
        }
        int next = 0;
        float prev = Float.NaN, min = Float.MAX_VALUE, max = 0f, sum = 0f;
        int frames = 0;
        long settleWall = arrival[0] + Math.round(8 * wallPerUpdate);
        long endWall = arrival[updates - 1] - Math.round(2 * wallPerUpdate);
        for (long wall = arrival[0]; wall < endWall; wall += FRAME_MS) {
            while (next < updates && arrival[next] <= wall) {
                long t = clock.onUpdate(1000L + next * 20L, arrival[next]);
                s.observe(id, next * BLOCKS_PER_UPDATE, 0, t);
                next++;
            }
            float x = s.x(id, 0, clock.now(wall));
            if (wall >= settleWall && !Float.isNaN(prev)) {
                float step = x - prev;
                min = Math.min(min, step);
                max = Math.max(max, step);
                sum += step;
                frames++;
            }
            prev = x;
        }
        return new float[]{min, max, sum / frames};
    }

    private static void assertSmooth(double tps, double jitterMs) {
        float[] st = replay(tps, jitterMs, 80);
        float real = (float) (BLOCKS_PER_UPDATE * tps / 20.0 * FRAME_MS / 1000.0);
        String what = tps + " TPS, jitter " + jitterMs + ": min " + st[0] + " max " + st[1] + " real " + real;
        assertEquals(real, st[2], real * 0.05f, "average pace follows the server: " + what);
        assertTrue(st[0] > real * 0.6f, "never stalls: " + what);
        assertTrue(st[1] < real * 1.4f, "never dashes: " + what);
    }

    @Test
    void steadyServerAnyTps() {
        assertSmooth(20, 0);
        assertSmooth(17, 0);
        assertSmooth(15, 0);
        assertSmooth(10, 0);
    }

    @Test
    void irregularPackets() {
        assertSmooth(20, 150);
        assertSmooth(15, 150);
        assertSmooth(10, 150);
    }

    @Test
    void stalledServerEasesToAStopAtTheNewestUpdate() {
        MapPlaybackClock clock = new MapPlaybackClock();
        clock.onUpdate(100, 0);
        clock.onUpdate(120, 1000);
        long prev = clock.now(1000);
        long prevStep = Long.MAX_VALUE;
        for (long wall = 1016; wall < 20_000; wall += 16) {
            long t = clock.now(wall);
            assertTrue(t >= prev && t <= 120 * 50L, "never backwards nor past the newest update");
            prev = t;
        }
        assertTrue(120 * 50L - prev < 50, "ends at the newest update: " + prev);
    }

    @Test
    void reopenedLaterResyncs() {
        MapPlaybackClock clock = new MapPlaybackClock();
        clock.onUpdate(100, 0);
        clock.now(500);
        clock.onUpdate(100 + 20 * 600, 600_000);
        assertEquals((100 + 20 * 600) * 50L - MapPlaybackClock.BUFFER_MS, clock.now(600_000));
    }
}
