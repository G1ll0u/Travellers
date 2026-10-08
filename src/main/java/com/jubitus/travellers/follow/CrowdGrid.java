package com.jubitus.travellers.follow;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.util.math.MathHelper;

import java.util.*;

/**
 * Spatial bucket of registered members, rebuilt at most once per world tick.
 * Lets many followers query nearby group-mates for crowd-avoidance scoring
 * in O(1) instead of each running its own world.getEntitiesWithinAABB() scan.
 * Flat array indexed directly by cell offset rather than a HashMap: with a tightly
 * clustered group (hundreds of followers in a handful of cells), a Long-keyed HashMap
 * treeifies its buckets because Long.hashCode() XORs the packed cx/cz halves back
 * together, collapsing entropy for small nearby cell coordinates — array indexing has
 * no hash step at all.
 */
final class CrowdGrid {

    private static final double CROWD_CELL_SIZE = 4.0D;
    /** Defensive cap on grid span (cells) so one far-away straggler can't blow up the array. */
    private static final int CROWD_GRID_MAX_SPAN = 160;

    private List<EntityTravellerWorker>[] crowdGridCells = emptyCrowdGrid();
    private int crowdGridMinCx, crowdGridMinCz, crowdGridWidth, crowdGridHeight;
    private long crowdGridTick = Long.MIN_VALUE;

    @SuppressWarnings("unchecked")
    private static List<EntityTravellerWorker>[] emptyCrowdGrid() {
        return new List[0];
    }

    @SuppressWarnings("unchecked")
    private void ensureCrowdGrid(Collection<LeaderFormation.RegisteredMember> registered, long now) {
        if (crowdGridTick == now) return;
        crowdGridTick = now;

        int minCx = Integer.MAX_VALUE, maxCx = Integer.MIN_VALUE;
        int minCz = Integer.MAX_VALUE, maxCz = Integer.MIN_VALUE;
        List<EntityTravellerWorker> live = new ArrayList<>(registered.size());

        for (LeaderFormation.RegisteredMember m : registered) {
            EntityTravellerWorker w = m.worker;
            if ((now - m.lastSeen) > LeaderFormation.MEMBER_EXPIRY_TICKS || w.isDead) continue;

            live.add(w);
            int cx = MathHelper.floor(w.posX / CROWD_CELL_SIZE);
            int cz = MathHelper.floor(w.posZ / CROWD_CELL_SIZE);
            if (cx < minCx) minCx = cx;
            if (cx > maxCx) maxCx = cx;
            if (cz < minCz) minCz = cz;
            if (cz > maxCz) maxCz = cz;
        }

        if (live.isEmpty()) {
            crowdGridWidth = 0;
            crowdGridHeight = 0;
            crowdGridCells = emptyCrowdGrid();
            return;
        }

        int width = Math.min(maxCx - minCx + 1, CROWD_GRID_MAX_SPAN);
        int height = Math.min(maxCz - minCz + 1, CROWD_GRID_MAX_SPAN);
        crowdGridMinCx = minCx;
        crowdGridMinCz = minCz;
        crowdGridWidth = width;
        crowdGridHeight = height;

        List<EntityTravellerWorker>[] cells = new List[width * height];

        for (EntityTravellerWorker w : live) {
            int cx = MathHelper.floor(w.posX / CROWD_CELL_SIZE) - minCx;
            int cz = MathHelper.floor(w.posZ / CROWD_CELL_SIZE) - minCz;
            if (cx < 0 || cx >= width || cz < 0 || cz >= height) continue; // outside capped span

            int idx = cx * height + cz;
            List<EntityTravellerWorker> bucket = cells[idx];
            if (bucket == null) {
                bucket = new ArrayList<>(4);
                cells[idx] = bucket;
            }
            bucket.add(w);
        }

        crowdGridCells = cells;
    }

    /**
     * Group-mates within the 3x3 cells around (x, z), excluding {@code self}. No owner check
     * needed: a worker only ever registers into this context when
     * {@code getEffectiveFollowTarget() == leader}, which itself requires a matching
     * follow-owner (see {@code EntityTravellerWorker.getEffectiveFollowTarget}) — every
     * registered member of this context is guaranteed to already share the same owner.
     */
    List<EntityTravellerWorker> neighbors(Collection<LeaderFormation.RegisteredMember> registered, double x, double z,
                                          long now, EntityTravellerWorker self) {
        ensureCrowdGrid(registered, now);
        if (crowdGridWidth == 0) return Collections.emptyList();

        int cx = MathHelper.floor(x / CROWD_CELL_SIZE) - crowdGridMinCx;
        int cz = MathHelper.floor(z / CROWD_CELL_SIZE) - crowdGridMinCz;

        List<EntityTravellerWorker> result = new ArrayList<>(8);
        for (int dx = -1; dx <= 1; dx++) {
            int gx = cx + dx;
            if (gx < 0 || gx >= crowdGridWidth) continue;
            for (int dz = -1; dz <= 1; dz++) {
                int gz = cz + dz;
                if (gz < 0 || gz >= crowdGridHeight) continue;

                List<EntityTravellerWorker> bucket = crowdGridCells[gx * crowdGridHeight + gz];
                if (bucket == null) continue;
                for (EntityTravellerWorker w : bucket) {
                    if (w == self) continue;
                    result.add(w);
                }
            }
        }
        return result;
    }
}
