package com.jubitus.travellers.travel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StuckRecordTest {

    private static final TravelStuckLadder.Timings T = new TravelStuckLadder.Timings(
            20, 40, 60, 120, 160, 200, 100, 40, 600, 20);

    @Test
    void pacingAPocketStopsCountingAsProgress() {
        StuckRecord r = new StuckRecord();
        int newCells = 0;
        for (int lap = 0; lap < 20; lap++) {
            for (double x = 0; x <= 5; x += 0.3) newCells += r.visit(x, 64, 0.5) ? 1 : 0;
            for (double x = 5; x >= 0; x -= 0.3) newCells += r.visit(x, 64, 0.5) ? 1 : 0;
        }
        assertEquals(6, newCells, "only the first pass over the six cells is progress");
    }

    @Test
    void walkingAwayKeepsProgressing() {
        StuckRecord r = new StuckRecord();
        int ticksWithoutProgress = 0;
        int worst = 0;
        for (double x = 0; x < 600; x += 0.15) {
            if (r.visit(x, 64, x * 0.3)) {
                ticksWithoutProgress = 0;
            } else {
                worst = Math.max(worst, ++ticksWithoutProgress);
            }
        }
        assertTrue(worst < 10, "a slow walker enters a new cell well within a second, got " + worst);
    }

    @Test
    void movingCatchupGoalKeepsTheEvidence() {
        StuckRecord r = new StuckRecord();
        Object catchup = new Object();
        r.beginEpisode(catchup);
        for (int i = 0; i < 45; i++) r.ladder.tick(false, 0, 0, true, T);
        assertEquals(2, r.ladder.rung());
        r.beginEpisode(catchup);
        assertEquals(2, r.ladder.rung(), "same episode, nothing reset");
        r.beginEpisode("another stop");
        assertEquals(0, r.ladder.rung(), "a new destination starts over");
        assertFalse(r.visit(0, 64, 0) && r.visit(0.2, 64, 0.2), "staying in one cell is not progress");
    }
}
