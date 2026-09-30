package com.jubitus.travellers.world.pathing.route;

import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RouteCacheTest {

    private static TravelPlan plan(int... xs) {
        java.util.List<BlockPos> pts = new java.util.ArrayList<>();
        for (int x : xs) pts.add(new BlockPos(x, 64, 0));
        return new TravelPlan(pts, 0L, TravelPlan.QUALITY_COARSE, true);
    }

    @Test
    void putThenGetReturnsAnIndependentCopy() {
        RouteCache c = new RouteCache();
        c.put("k", 0, plan(0, 100, 200), 1000L, 0, 0, 500, 500);
        TravelPlan got = c.get("k", 1000L, 12000);
        assertNotNull(got);
        assertEquals(3, got.polyline.size());
        got.polyline.clear(); // must not affect the cache
        assertEquals(3, c.get("k", 1000L, 12000).polyline.size());
    }

    @Test
    void entriesExpireAfterTtl() {
        RouteCache c = new RouteCache();
        c.put("k", 0, plan(0, 100), 1000L, 0, 0, 10, 10);
        assertNotNull(c.get("k", 1000L + 12000, 12000));
        assertNull(c.get("k", 1000L + 12001, 12000));
        assertEquals(0, c.size(), "expired entry is dropped on read");
    }

    @Test
    void oldestEntryIsEvictedOverCap() {
        int saved = TravellersConfig.ROUTING.routeCacheMaxEntries;
        TravellersConfig.ROUTING.routeCacheMaxEntries = 16; // clamp floor in put()
        try {
            RouteCache c = new RouteCache();
            for (int i = 0; i < 20; i++) c.put("k" + i, 0, plan(0, i), 0L, 0, 0, 1, 1);
            assertEquals(16, c.size());
            assertNull(c.get("k0", 0L, 99999));
            assertNotNull(c.get("k19", 0L, 99999));
        } finally {
            TravellersConfig.ROUTING.routeCacheMaxEntries = saved;
        }
    }

    @Test
    void invalidateBoxDropsOnlyIntersectingEntriesInTheSameDimension() {
        RouteCache c = new RouteCache();
        c.put("a", 0, plan(0, 1), 0L, 0, 0, 100, 100);      // dim 0, near origin
        c.put("b", 0, plan(0, 1), 0L, 5000, 5000, 6000, 6000); // dim 0, far
        c.put("c", 1, plan(0, 1), 0L, 0, 0, 100, 100);      // dim 1, near origin
        int removed = c.invalidateBox(0, 50, 50, 60, 60);
        assertEquals(1, removed);
        assertNull(c.get("a", 0L, 99999));
        assertNotNull(c.get("b", 0L, 99999));
        assertNotNull(c.get("c", 0L, 99999));
    }

    @Test
    void accessOrderKeepsARecentlyReadEntryAlive() {
        int saved = TravellersConfig.ROUTING.routeCacheMaxEntries;
        TravellersConfig.ROUTING.routeCacheMaxEntries = 16;
        try {
            RouteCache c = new RouteCache();
            for (int i = 0; i < 16; i++) c.put("k" + i, 0, plan(0, i), 0L, 0, 0, 1, 1);
            c.get("k0", 0L, 99999);                 // touch k0 -> most-recently-used
            c.put("k16", 0, plan(0, 16), 0L, 0, 0, 1, 1); // evicts k1, not k0
            assertNotNull(c.get("k0", 0L, 99999));
            assertNull(c.get("k1", 0L, 99999));
        } finally {
            TravellersConfig.ROUTING.routeCacheMaxEntries = saved;
        }
    }
}
