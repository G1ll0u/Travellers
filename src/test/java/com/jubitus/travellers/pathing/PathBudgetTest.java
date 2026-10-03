package com.jubitus.travellers.pathing;

import com.jubitus.travellers.config.TravellersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PathBudgetTest {

    @BeforeEach
    void reset() {
        PathBudget.resetForTest();
    }

    @Test
    void rowCapResetsNextTick() {
        int cap = TravellersConfig.ROUTING.routeJobsPerTick;
        int granted = 0;
        for (int i = 0; i < cap + 5; i++) if (PathBudget.tryAcquire(100L, PathBudget.Client.ITINERARY)) granted++;
        assertEquals(cap, granted);
        assertEquals(PathBudget.Result.GRANTED, PathBudget.acquire(101L, PathBudget.Client.ITINERARY, 0, 0));
    }

    @Test
    void rowsAreIndependent() {
        int cap = TravellersConfig.ROUTING.routeJobsPerTick;
        for (int i = 0; i < cap; i++) PathBudget.tryAcquire(100L, PathBudget.Client.ITINERARY);
        assertEquals(PathBudget.Result.FULL, PathBudget.acquire(100L, PathBudget.Client.ITINERARY, 0, 0));
        assertEquals(PathBudget.Result.GRANTED, PathBudget.acquire(100L, PathBudget.Client.COMBAT, 0, 0));
    }

    @Test
    void travelStaggersNormalButNotUrgent() {
        assertEquals(PathBudget.Result.GRANTED, PathBudget.acquire(20L, PathBudget.Client.TRAVEL, 10, 0));
        assertEquals(PathBudget.Result.NOT_ITS_TURN, PathBudget.acquire(20L, PathBudget.Client.TRAVEL, 11, 1));
        assertEquals(PathBudget.Result.GRANTED, PathBudget.acquire(20L, PathBudget.Client.TRAVEL, 11, 2));
    }

    @Test
    void combatUrgentGetsTwoMore() {
        int granted = 0;
        for (int i = 0; i < 40; i++) if (PathBudget.acquire(5L, PathBudget.Client.COMBAT, i, 1) == PathBudget.Result.GRANTED) granted++;
        assertEquals(24, granted);
        for (int i = 0; i < 40; i++) if (PathBudget.acquire(5L, PathBudget.Client.COMBAT, i, 2) == PathBudget.Result.GRANTED) granted++;
        assertEquals(26, granted);
    }

    @Test
    void travelUrgentHasItsOwnCount() {
        for (int i = 0; i < 200; i++) PathBudget.acquire(30L, PathBudget.Client.TRAVEL, i * 10, 0);
        assertEquals(PathBudget.Result.FULL, PathBudget.acquire(30L, PathBudget.Client.TRAVEL, 0, 0));
        assertEquals(PathBudget.Result.GRANTED, PathBudget.acquire(30L, PathBudget.Client.TRAVEL, 0, 2));
    }
}
