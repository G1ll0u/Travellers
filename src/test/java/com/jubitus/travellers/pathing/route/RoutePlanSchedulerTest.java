package com.jubitus.travellers.pathing.route;

import com.jubitus.travellers.config.TravellersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutePlanSchedulerTest {

    @BeforeEach
    void reset() {
        RoutePlanScheduler.resetForTest();
        RoutePlanScheduler.STAGGER_TICKS = 1; // disable staggering for the budget tests
    }

    @Test
    void honoursThePerTickBudgetThenResetsNextTick() {
        int saved = TravellersConfig.ROUTING.routeJobsPerTick;
        TravellersConfig.ROUTING.routeJobsPerTick = 3;
        try {
            int granted = 0;
            for (int e = 0; e < 10; e++) if (RoutePlanScheduler.tryAcquire(100L, e, 0)) granted++;
            assertEquals(3, granted);
            // same tick -> still exhausted
            assertFalse(RoutePlanScheduler.tryAcquire(100L, 999, 0));
            // next tick -> budget refreshed
            assertTrue(RoutePlanScheduler.tryAcquire(101L, 0, 0));
        } finally {
            TravellersConfig.ROUTING.routeJobsPerTick = saved;
        }
    }

    @Test
    void staggerLetsOnlyMatchingEntityIdsThroughOnAGivenTick() {
        RoutePlanScheduler.STAGGER_TICKS = 4;
        TravellersConfig.ROUTING.routeJobsPerTick = 999;
        // tick 8 -> floorMod(8,4)==0 -> only entity ids with id%4==0 pass
        assertTrue(RoutePlanScheduler.tryAcquire(8L, 0, 0));
        assertTrue(RoutePlanScheduler.tryAcquire(8L, 4, 0));
        assertFalse(RoutePlanScheduler.tryAcquire(8L, 1, 0));
        // priority 2 bypasses the stagger
        assertTrue(RoutePlanScheduler.tryAcquire(8L, 1, 2));
    }
}
