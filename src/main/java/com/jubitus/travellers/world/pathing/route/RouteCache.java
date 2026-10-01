package com.jubitus.travellers.world.pathing.route;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Access-ordered LRU of computed polylines, keyed by whatever string the caller chooses (normally
 * a settlement-centre pair + mount flag). This is the lever that makes "thousands of travellers"
 * cheap: the first unit on a leg pays the search, the rest read the cache.
 *
 * <p>Entries carry a bounding box so a {@code REAL_SCAN} upgrade can invalidate the ones it touches
 * ({@link #invalidateBox}). Guarded by its own monitor; reads return a private copy of the polyline.
 */
public final class RouteCache {

    private static final class Entry {
        final int dimension;
        final List<BlockPos> polyline;
        final long tick;
        final byte quality;
        final boolean reachedGoal;
        final byte[] kinds;
        final int minX, minZ, maxX, maxZ;
        // Diagnostics: where the search that filled this entry started, and how many road vertices it saw.
        final int fromX, fromZ, roadVertices;

        Entry(int dimension, List<BlockPos> polyline, long tick, byte quality, boolean reachedGoal, byte[] kinds,
              int minX, int minZ, int maxX, int maxZ, int fromX, int fromZ, int roadVertices) {
            this.dimension = dimension;
            this.polyline = polyline;
            this.tick = tick;
            this.quality = quality;
            this.reachedGoal = reachedGoal;
            this.kinds = kinds;
            this.fromX = fromX;
            this.fromZ = fromZ;
            this.roadVertices = roadVertices;
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
        }
    }

    private final LinkedHashMap<String, Entry> map = new LinkedHashMap<>(64, 0.75f, true);

    public synchronized void put(String key, int dimension, TravelPlan plan, long now,
                                 int minX, int minZ, int maxX, int maxZ, int fromX, int fromZ, int roadVertices) {
        map.put(key, new Entry(dimension, new ArrayList<>(plan.polyline), now, plan.quality,
                plan.reachedGoal, plan.kinds == null ? null : plan.kinds.clone(), minX, minZ, maxX, maxZ,
                fromX, fromZ, roadVertices));
        int cap = Math.max(16, com.jubitus.travellers.config.TravellersConfig.ROUTING.routeCacheMaxEntries);
        Iterator<Map.Entry<String, Entry>> it = map.entrySet().iterator();
        while (map.size() > cap && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    /** Returns a fresh {@link TravelPlan} copy, or {@code null} if missing or older than {@code ttlTicks}. */
    public synchronized TravelPlan get(String key, long now, int ttlTicks) {
        Entry e = live(key, now, ttlTicks);
        if (e == null) return null;
        return new TravelPlan(new ArrayList<>(e.polyline), e.tick, e.quality, e.reachedGoal,
                e.kinds == null ? null : e.kinds.clone());
    }

    /** Diagnostics for a live entry: {fromX, fromZ, roadVertices}, or null. */
    public synchronized int[] origin(String key, long now, int ttlTicks) {
        Entry e = live(key, now, ttlTicks);
        return e == null ? null : new int[]{e.fromX, e.fromZ, e.roadVertices};
    }

    private Entry live(String key, long now, int ttlTicks) {
        Entry e = map.get(key);
        if (e == null) return null;
        if (now - e.tick > ttlTicks) {
            map.remove(key);
            return null;
        }
        return e;
    }

    /** Drops every entry in {@code dimension} whose bounding box intersects the given box. */
    public synchronized int invalidateBox(int dimension, int minX, int minZ, int maxX, int maxZ) {
        int removed = 0;
        Iterator<Map.Entry<String, Entry>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            if (e.dimension != dimension) continue;
            if (e.maxX < minX || e.minX > maxX || e.maxZ < minZ || e.minZ > maxZ) continue;
            it.remove();
            removed++;
        }
        return removed;
    }

    public synchronized int size() {
        return map.size();
    }

    public synchronized void clear() {
        map.clear();
    }
}
