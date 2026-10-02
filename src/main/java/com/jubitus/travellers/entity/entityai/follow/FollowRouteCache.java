package com.jubitus.travellers.entity.entityai.follow;

import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Iterator;

/**
 * Shares a recently found real path with nearby steering-mode followers, so a group doesn't need
 * its own successful A* call to route around the same obstacle one nearby traveller already found
 * a way past.
 *
 * <p>Recording stays keyed by identity (whoever asked for the path - a group leader, or a solo
 * traveller/worker) so a given identity only ever has one live cached route, replaced as it walks.
 * Lookup is spatial: any nearby route within {@code sharedRouteCorridorCellSize}-block cells is a
 * candidate, filtered by {@link TravellersConfig.WorkerFormation#sharedRouteCorridorDirectionToleranceDeg}
 * so a follower never reuses a path heading the wrong way. Setting
 * {@link TravellersConfig.WorkerFormation#sharedRouteCorridorWorldWide} to {@code false} narrows the
 * search back down to routes recorded by the caller's own {@code pathOwner} identity, i.e. the
 * original "one leader's own group" behaviour, without a second implementation to maintain.
 */
public final class FollowRouteCache {

    private static final long CLEANUP_INTERVAL_TICKS = 400L;

    /** A cached point this close (or closer) to the querier is skipped rather than handed back as
     * a target. Without this floor, a follower converging on a cached waypoint gets re-issued a
     * point closer and closer to its own feet every tick as it approaches - vanilla's own
     * {@code EntityMoveHelper} spins an entity in place when told to steer toward a point that's
     * basically underfoot (the same idiom {@code MountedFollowPacer.AIM_DEAD_ZONE} guards against
     * elsewhere in this codebase). Once nothing on any nearby route qualifies beyond this floor,
     * {@link #findNearestUsableWaypoint} returns {@code null} and the caller falls through to a
     * real pathfind - the intended "last resort" behaviour, not an indefinite substitute for one. */
    // INVARIANT: never hand a follower a waypoint under its own feet (vanilla underfoot spin).
    private static final double MIN_SNAP_DISTANCE = 2.0D;
    private static final double MIN_SNAP_DISTANCE_SQ = MIN_SNAP_DISTANCE * MIN_SNAP_DISTANCE;

    /** All cached routes, in insertion/replacement order (oldest first) - lets eviction drop the
     * least-recently-recorded one once {@code sharedRouteCorridorMaxCachedRoutes} is exceeded. */
    private static final LinkedHashMap<UUID, CachedRoute> ROUTES = new LinkedHashMap<>();

    /** Spatial index: which routes pass through each grid cell. A route appears once per distinct
     * cell its points fall in (see {@link CachedRoute#cells}). */
    private static final Map<CellKey, List<CachedRoute>> CELLS = new HashMap<>();

    private static long lastCleanupTick = Long.MIN_VALUE;

    private FollowRouteCache() {
    }

    public static void record(World world, EntityLivingBase pathOwner, @Nullable Path path) {
        if (path == null || path.getCurrentPathLength() <= 0) return;

        long now = world.getTotalWorldTime();
        cleanup(now);

        int len = path.getCurrentPathLength();
        List<Vec3d> points = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            PathPoint p = path.getPathPointFromIndex(i);
            points.add(new Vec3d(p.x + 0.5D, p.y, p.z + 0.5D));
        }

        Vec3d start = points.get(0);
        Vec3d end = points.get(points.size() - 1);
        double bearing = Math.atan2(end.z - start.z, end.x - start.x);

        int dim = world.provider.getDimension();
        int cellSize = Math.max(8, TravellersConfig.WORKER_FORMATION.sharedRouteCorridorCellSize);

        UUID ownerId = pathOwner.getUniqueID();
        CachedRoute previous = ROUTES.remove(ownerId);
        if (previous != null) removeFromCells(previous);

        CachedRoute route = new CachedRoute(points, now, bearing, ownerId);
        insertIntoCells(route, dim, cellSize);
        ROUTES.put(ownerId, route);

        evictOldestIfOverCap();
    }

    @Nullable
    public static Vec3d findNearestUsableWaypoint(World world,
                                                  EntityLivingBase pathOwner,
                                                  double workerX,
                                                  double workerZ,
                                                  double bearingToOwnGoal,
                                                  double maxSnapDistance,
                                                  int ttlTicks) {
        TravellersConfig.WorkerFormation wf = TravellersConfig.WORKER_FORMATION;
        boolean worldWide = wf.sharedRouteCorridorWorldWide;
        int dim = world.provider.getDimension();
        long now = world.getTotalWorldTime();
        int cellSize = Math.max(8, wf.sharedRouteCorridorCellSize);
        double toleranceRad = Math.toRadians(wf.sharedRouteCorridorDirectionToleranceDeg);
        UUID ownerId = pathOwner.getUniqueID();

        int cx = Math.floorDiv((int) Math.floor(workerX), cellSize);
        int cz = Math.floorDiv((int) Math.floor(workerZ), cellSize);

        double maxSq = maxSnapDistance * maxSnapDistance;
        Vec3d best = null;
        double bestScore = Double.MAX_VALUE;

        // The querier's own cell plus its 8 neighbours - a corridor a couple of cells wide would
        // otherwise be missed for a route recorded just across a cell boundary.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                List<CachedRoute> cellRoutes = CELLS.get(new CellKey(dim, cx + dx, cz + dz));
                if (cellRoutes == null) continue;

                for (CachedRoute route : cellRoutes) {
                    long age = now - route.createdTick;
                    if (age < 0 || age > ttlTicks) continue; // stale; periodic cleanup() will drop it

                    if (worldWide) {
                        if (angleDiff(route.bearing, bearingToOwnGoal) > toleranceRad) continue;
                    } else if (!route.ownerId.equals(ownerId)) {
                        continue;
                    }

                    List<Vec3d> points = route.points;
                    for (int i = 0; i < points.size(); i++) {
                        Vec3d p = points.get(i);
                        double pdx = p.x - workerX;
                        double pdz = p.z - workerZ;
                        double distSq = pdx * pdx + pdz * pdz;
                        if (distSq > maxSq || distSq < MIN_SNAP_DISTANCE_SQ) continue;

                        // Slight bias toward waypoints further along the route, so a follower
                        // advances instead of always snapping back to the route's starting point.
                        double score = distSq - i * 0.05D;
                        if (score < bestScore) {
                            bestScore = score;
                            best = p;
                        }
                    }
                }
            }
        }

        return best;
    }

    /** Smallest absolute difference between two angles (radians), in [0, PI]. */
    private static double angleDiff(double a, double b) {
        double d = Math.abs(a - b) % (Math.PI * 2.0D);
        return d > Math.PI ? (Math.PI * 2.0D - d) : d;
    }

    private static void insertIntoCells(CachedRoute route, int dim, int cellSize) {
        Set<CellKey> cells = new LinkedHashSet<>();
        for (Vec3d p : route.points) {
            int cx = Math.floorDiv((int) Math.floor(p.x), cellSize);
            int cz = Math.floorDiv((int) Math.floor(p.z), cellSize);
            cells.add(new CellKey(dim, cx, cz));
        }
        for (CellKey cell : cells) {
            CELLS.computeIfAbsent(cell, k -> new ArrayList<>()).add(route);
        }
        route.cells = cells;
    }

    private static void removeFromCells(CachedRoute route) {
        if (route.cells == null) return;
        for (CellKey cell : route.cells) {
            List<CachedRoute> list = CELLS.get(cell);
            if (list == null) continue;
            list.remove(route);
            if (list.isEmpty()) CELLS.remove(cell);
        }
    }

    private static void evictOldestIfOverCap() {
        int cap = Math.max(1, TravellersConfig.WORKER_FORMATION.sharedRouteCorridorMaxCachedRoutes);
        Iterator<Map.Entry<UUID, CachedRoute>> it = ROUTES.entrySet().iterator();
        while (ROUTES.size() > cap && it.hasNext()) {
            CachedRoute oldest = it.next().getValue();
            it.remove();
            removeFromCells(oldest);
        }
    }

    private static void cleanup(long now) {
        if (lastCleanupTick != Long.MIN_VALUE && (now - lastCleanupTick) < CLEANUP_INTERVAL_TICKS) return;
        lastCleanupTick = now;

        Iterator<Map.Entry<UUID, CachedRoute>> it = ROUTES.entrySet().iterator();
        while (it.hasNext()) {
            CachedRoute route = it.next().getValue();
            if ((now - route.createdTick) > CLEANUP_INTERVAL_TICKS) {
                it.remove();
                removeFromCells(route);
            }
        }
    }

    private static final class CellKey {
        private final int dimension;
        private final int cellX;
        private final int cellZ;

        private CellKey(int dimension, int cellX, int cellZ) {
            this.dimension = dimension;
            this.cellX = cellX;
            this.cellZ = cellZ;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof CellKey)) return false;
            CellKey other = (CellKey) obj;
            return dimension == other.dimension && cellX == other.cellX && cellZ == other.cellZ;
        }

        @Override
        public int hashCode() {
            int result = dimension;
            result = 31 * result + cellX;
            result = 31 * result + cellZ;
            return result;
        }
    }

    private static final class CachedRoute {
        private final List<Vec3d> points;
        private final long createdTick;
        private final double bearing;
        private final UUID ownerId;
        private Set<CellKey> cells;

        private CachedRoute(List<Vec3d> points, long createdTick, double bearing, UUID ownerId) {
            this.points = points;
            this.createdTick = createdTick;
            this.bearing = bearing;
            this.ownerId = ownerId;
        }
    }
}
