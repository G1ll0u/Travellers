package com.jubitus.travellers.pathing.route;

import com.jubitus.travellers.pathing.terrain.TerrainGridView;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Immutable input to one route-planner worker task. Everything world-derived is resolved on the
 * server thread before construction; the worker reads only these fields and the {@link TerrainGridView}.
 */
public final class TravelRouteJob {

    public final int dimension;
    public final UUID callbackKey;

    public final int originX;
    public final int originZ;
    public final int destX;
    public final int destZ;

    public final int boxMinX;
    public final int boxMinZ;
    public final int boxMaxX;
    public final int boxMaxZ;

    public final boolean mountFlag;
    @Nullable
    public final String cultureId; // Plan B; unused for now

    public final RouteCostParams params;
    public final long requestTick;
    public final TerrainGridView gridView;

    public final int nodeSpacing;
    public final int nodeBudget;
    // Roads usable by this search (empty when the unit may not use roads).
    public final RoadLayer roads;
    // Walled villages the walked route goes around.
    public final WallRings walls;

    public TravelRouteJob(int dimension, UUID callbackKey, int originX, int originZ, int destX, int destZ,
                          int boxMinX, int boxMinZ, int boxMaxX, int boxMaxZ, boolean mountFlag,
                          @Nullable String cultureId, RouteCostParams params, long requestTick,
                          TerrainGridView gridView, int nodeSpacing, int nodeBudget) {
        this(dimension, callbackKey, originX, originZ, destX, destZ, boxMinX, boxMinZ, boxMaxX, boxMaxZ,
                mountFlag, cultureId, params, requestTick, gridView, nodeSpacing, nodeBudget, RoadLayer.EMPTY);
    }

    public TravelRouteJob(int dimension, UUID callbackKey, int originX, int originZ, int destX, int destZ,
                          int boxMinX, int boxMinZ, int boxMaxX, int boxMaxZ, boolean mountFlag,
                          @Nullable String cultureId, RouteCostParams params, long requestTick,
                          TerrainGridView gridView, int nodeSpacing, int nodeBudget, RoadLayer roads) {
        this(dimension, callbackKey, originX, originZ, destX, destZ, boxMinX, boxMinZ, boxMaxX, boxMaxZ,
                mountFlag, cultureId, params, requestTick, gridView, nodeSpacing, nodeBudget, roads, WallRings.EMPTY);
    }

    public TravelRouteJob(int dimension, UUID callbackKey, int originX, int originZ, int destX, int destZ,
                          int boxMinX, int boxMinZ, int boxMaxX, int boxMaxZ, boolean mountFlag,
                          @Nullable String cultureId, RouteCostParams params, long requestTick,
                          TerrainGridView gridView, int nodeSpacing, int nodeBudget, RoadLayer roads,
                          WallRings walls) {
        this.dimension = dimension;
        this.callbackKey = callbackKey;
        this.originX = originX;
        this.originZ = originZ;
        this.destX = destX;
        this.destZ = destZ;
        this.boxMinX = boxMinX;
        this.boxMinZ = boxMinZ;
        this.boxMaxX = boxMaxX;
        this.boxMaxZ = boxMaxZ;
        this.mountFlag = mountFlag;
        this.cultureId = cultureId;
        this.params = params;
        this.requestTick = requestTick;
        this.gridView = gridView;
        this.nodeSpacing = Math.max(16, nodeSpacing);
        this.nodeBudget = Math.max(16, nodeBudget);
        this.roads = roads == null ? RoadLayer.EMPTY : roads;
        this.walls = walls == null ? WallRings.EMPTY : walls;
    }

    /**
     * Builds a job whose search box is the origin->dest bounding rectangle inflated on every side by
     * {@code boxInflationFrac} of the leg's length, plus a two-node margin - so a leg running along
     * one axis still has room to detour (or reach a road) sideways. The caller has already
     * {@code ensureEstimated} + {@code captureView}'d that box.
     */
    public static int[] computeBox(int originX, int originZ, int destX, int destZ,
                                   double boxInflationFrac, int nodeSpacing) {
        int minX = Math.min(originX, destX);
        int maxX = Math.max(originX, destX);
        int minZ = Math.min(originZ, destZ);
        int maxZ = Math.max(originZ, destZ);
        double len = Math.sqrt((double) (maxX - minX) * (maxX - minX) + (double) (maxZ - minZ) * (maxZ - minZ));
        int inf = (int) Math.round(len * boxInflationFrac) + 2 * nodeSpacing;
        return new int[]{minX - inf, minZ - inf, maxX + inf, maxZ + inf};
    }
}
