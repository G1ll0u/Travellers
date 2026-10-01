package com.jubitus.travellers.world.pathing.route;

import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the bounded route-planner thread pool ({@code routePlannerThreads}), the finished-result
 * queue and the {@link RouteCache}. Static singleton, like the schedulers.
 *
 * <p>Route planning is not latency-critical: a unit keeps walking its previous polyline (or a
 * straight line) until a new one lands via {@link #drainResults}.
 */
public final class TravelRoutePlanner {

    private static ExecutorService exec;
    private static final ConcurrentLinkedQueue<RouteResult> RESULTS = new ConcurrentLinkedQueue<>();
    private static final Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final RouteCache CACHE = new RouteCache();

    private TravelRoutePlanner() {
    }

    public static final class RouteResult {
        public final int dimension;
        public final UUID key;
        public final TravelPlan plan;
        public final boolean fromCache;
        // Diagnostics: where the search behind this plan started, and how many road vertices it saw.
        public final int fromX, fromZ, roadVertices;

        RouteResult(int dimension, UUID key, TravelPlan plan, boolean fromCache, int fromX, int fromZ, int roadVertices) {
            this.dimension = dimension;
            this.key = key;
            this.plan = plan;
            this.fromCache = fromCache;
            this.fromX = fromX;
            this.fromZ = fromZ;
            this.roadVertices = roadVertices;
        }
    }

    private static synchronized ExecutorService exec() {
        if (exec == null || exec.isShutdown()) {
            int n = Math.max(1, TravellersConfig.ROUTING.routePlannerThreads);
            exec = Executors.newFixedThreadPool(n, r -> {
                Thread t = new Thread(r, "Travellers-RoutePlanner");
                t.setDaemon(true);
                return t;
            });
        }
        return exec;
    }

    /**
     * Server thread. Requests a plan for {@code job}, keyed for de-dup and caching by
     * {@code cacheKey}. Returns {@code true} if a plan is on the way (cache hit enqueued now, or a
     * worker job submitted), {@code false} if the scheduler denied a slot (caller uses its fallback).
     *
     * <p>Priority defaults to 0/1 (normal/mounted, every virtual caller) - see the 3-arg overload.
     */
    public static boolean request(WorldServer world, TravelRouteJob job, String cacheKey) {
        return request(world, job, cacheKey, job.mountFlag ? 1 : 0);
    }

    /**
     * Same as the 3-arg overload, with an explicit scheduler priority. {@code priority} 2 is
     * reserved for a loaded caller ({@code ItineraryPlanner} for a loaded owner): it skips
     * {@link RoutePlanScheduler}'s stagger but still respects its per-tick ceiling, so a loaded
     * traveller can't starve the virtual population's budget, it just doesn't wait its turn behind it.
     */
    public static boolean request(WorldServer world, TravelRouteJob job, String cacheKey, int priority) {
        long now = world.getTotalWorldTime();

        int ttl = Math.max(1, TravellersConfig.ROUTING.routeCacheTtlTicks);
        TravelPlan cached = CACHE.get(cacheKey, now, ttl);
        if (cached != null) {
            int[] o = CACHE.origin(cacheKey, now, ttl);
            RESULTS.add(new RouteResult(job.dimension, job.callbackKey, cached, true,
                    o != null ? o[0] : 0, o != null ? o[1] : 0, o != null ? o[2] : -1));
            return true;
        }

        if (!IN_FLIGHT.add(job.callbackKey)) return true; // already planning for this unit

        if (!RoutePlanScheduler.tryAcquire(world, job.callbackKey.hashCode(), priority)) {
            IN_FLIGHT.remove(job.callbackKey);
            return false;
        }

        exec().submit(() -> {
            try {
                TravelPlan plan = CoarseRouteSearch.run(job, now);
                CACHE.put(cacheKey, job.dimension, plan, now,
                        job.boxMinX, job.boxMinZ, job.boxMaxX, job.boxMaxZ, job.originX, job.originZ, job.roads.size());
                RESULTS.add(new RouteResult(job.dimension, job.callbackKey, plan, false,
                        job.originX, job.originZ, job.roads.size()));
            } catch (Throwable t) {
                System.err.println("[Travellers/Route] planner job failed: " + t);
            } finally {
                IN_FLIGHT.remove(job.callbackKey);
            }
        });
        return true;
    }

    /** Server thread: drains finished results for one dimension, leaving others queued. */
    public static List<RouteResult> drainResults(int dimension) {
        List<RouteResult> out = null;
        int n = RESULTS.size();
        for (int i = 0; i < n; i++) {
            RouteResult r = RESULTS.poll();
            if (r == null) break;
            if (r.dimension == dimension) {
                if (out == null) out = new ArrayList<>();
                out.add(r);
            } else {
                RESULTS.add(r);
            }
        }
        return out == null ? Collections.emptyList() : out;
    }

    /** A {@code REAL_SCAN} upgrade for a cell invalidates any cached route whose box covers it. */
    public static void onRealScan(int dimension, int cellX, int cellZ) {
        int wx = cellX * 16;
        int wz = cellZ * 16;
        CACHE.invalidateBox(dimension, wx, wz, wx + 16, wz + 16);
    }

    public static RouteCache cache() {
        return CACHE;
    }

    public static int inFlightCount() {
        return IN_FLIGHT.size();
    }

    public static int pendingResultCount() {
        return RESULTS.size();
    }

    /** Server stop. */
    public static void shutdown() {
        synchronized (TravelRoutePlanner.class) {
            if (exec != null) {
                exec.shutdownNow();
                exec = null;
            }
        }
        RESULTS.clear();
        IN_FLIGHT.clear();
        CACHE.clear();
    }
}
