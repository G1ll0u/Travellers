package com.jubitus.travellers.entity.entityAI.follow;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shares a leader's most recently found real path across nearby steering-mode followers,
 * so a large group doesn't need its own successful A* call to route around the same obstacle
 * one follower already found a way past.
 */
public final class FollowRouteCache {

    private static final long CLEANUP_INTERVAL_TICKS = 400L;

    private static final Map<RouteKey, CachedRoute> ROUTES = new HashMap<>();
    private static long lastCleanupTick = Long.MIN_VALUE;

    private FollowRouteCache() {
    }

    public static void record(World world, EntityLivingBase leader, @Nullable Path path) {
        if (path == null || path.getCurrentPathLength() <= 0) return;

        long now = world.getTotalWorldTime();
        cleanup(now);

        List<Vec3d> points = new ArrayList<>(path.getCurrentPathLength());
        for (int i = 0; i < path.getCurrentPathLength(); i++) {
            PathPoint p = path.getPathPointFromIndex(i);
            points.add(new Vec3d(p.x + 0.5D, p.y, p.z + 0.5D));
        }

        RouteKey key = new RouteKey(world.provider.getDimension(), leader.getUniqueID());
        ROUTES.put(key, new CachedRoute(points, now));
    }

    @Nullable
    public static Vec3d findNearestUsableWaypoint(World world,
                                                  EntityLivingBase leader,
                                                  double workerX,
                                                  double workerZ,
                                                  double maxSnapDistance,
                                                  int ttlTicks) {
        RouteKey key = new RouteKey(world.provider.getDimension(), leader.getUniqueID());
        CachedRoute route = ROUTES.get(key);
        if (route == null) return null;

        long age = world.getTotalWorldTime() - route.createdTick;
        if (age < 0 || age > ttlTicks) {
            ROUTES.remove(key);
            return null;
        }

        double maxSq = maxSnapDistance * maxSnapDistance;
        Vec3d best = null;
        double bestScore = Double.MAX_VALUE;

        List<Vec3d> points = route.points;
        for (int i = 0; i < points.size(); i++) {
            Vec3d p = points.get(i);
            double dx = p.x - workerX;
            double dz = p.z - workerZ;
            double distSq = dx * dx + dz * dz;
            if (distSq > maxSq) continue;

            // Slight bias toward waypoints further along the route, so a follower advances
            // instead of always snapping back to the route's starting point.
            double score = distSq - i * 0.05D;
            if (score < bestScore) {
                bestScore = score;
                best = p;
            }
        }

        return best;
    }

    private static void cleanup(long now) {
        if (lastCleanupTick != Long.MIN_VALUE && (now - lastCleanupTick) < CLEANUP_INTERVAL_TICKS) return;
        lastCleanupTick = now;
        ROUTES.entrySet().removeIf(e -> (now - e.getValue().createdTick) > CLEANUP_INTERVAL_TICKS);
    }

    private static final class RouteKey {
        private final int dimension;
        private final UUID leaderId;

        private RouteKey(int dimension, UUID leaderId) {
            this.dimension = dimension;
            this.leaderId = leaderId;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof RouteKey)) return false;
            RouteKey other = (RouteKey) obj;
            return dimension == other.dimension && leaderId.equals(other.leaderId);
        }

        @Override
        public int hashCode() {
            return 31 * dimension + leaderId.hashCode();
        }
    }

    private static final class CachedRoute {
        private final List<Vec3d> points;
        private final long createdTick;

        private CachedRoute(List<Vec3d> points, long createdTick) {
            this.points = points;
            this.createdTick = createdTick;
        }
    }
}
