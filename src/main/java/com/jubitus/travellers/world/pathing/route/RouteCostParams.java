package com.jubitus.travellers.world.pathing.route;

import com.jubitus.travellers.config.TravellersConfig;

/**
 * Immutable snapshot of the {@code TravellersConfig.ROUTING} cost weights, taken on the server
 * thread and handed to a route-planner worker so the worker reads no live config.
 */
public final class RouteCostParams {

    public final double stepWeight;
    public final double slopeWeight;
    public final double pitWeight;
    public final double roughnessWeight;
    public final double waterWeight;
    public final double deepOceanExtra;
    public final double deepWaterFrac; // 0..1
    public final double mountainBiomeWeight;
    public final double estimateUncertainty;
    public final double cultureBiomeWeight;
    public final double blockedCost;
    // Tolerance of the final polyline simplification, in blocks.
    public final double simplifyBlocks;
    // Cost per road block relative to a flat off-road block (1 / roadMaxDetourRatio).
    public final double roadCostFactor;
    // Boat crossings: off when boatCostFactor <= 0.
    public final double boatCostFactor;
    public final double boatEmbarkCost;
    public final int boatWater255;
    public final double boatMinCrossingBlocks;

    private RouteCostParams(double stepWeight, double slopeWeight, double pitWeight, double roughnessWeight,
                            double waterWeight, double deepOceanExtra, double deepWaterFrac,
                            double mountainBiomeWeight, double estimateUncertainty, double cultureBiomeWeight,
                            double blockedCost, double simplifyBlocks, double roadCostFactor,
                            double boatCostFactor, double boatEmbarkCost, double boatWaterFrac,
                            double boatMinCrossingBlocks) {
        this.stepWeight = stepWeight;
        this.slopeWeight = slopeWeight;
        this.pitWeight = pitWeight;
        this.roughnessWeight = roughnessWeight;
        this.waterWeight = waterWeight;
        this.deepOceanExtra = deepOceanExtra;
        this.deepWaterFrac = deepWaterFrac;
        this.mountainBiomeWeight = mountainBiomeWeight;
        this.estimateUncertainty = estimateUncertainty;
        this.cultureBiomeWeight = cultureBiomeWeight;
        this.blockedCost = blockedCost;
        this.simplifyBlocks = simplifyBlocks;
        this.roadCostFactor = roadCostFactor;
        this.boatCostFactor = boatCostFactor;
        this.boatEmbarkCost = boatEmbarkCost;
        this.boatWater255 = (int) Math.round(Math.max(0.0, Math.min(1.0, boatWaterFrac)) * 255.0);
        this.boatMinCrossingBlocks = boatMinCrossingBlocks;
    }

    public static RouteCostParams snapshot(boolean mounted) {
        TravellersConfig.Routing r = TravellersConfig.ROUTING;
        return new RouteCostParams(r.stepWeight, r.slopeWeight, r.pitWeight, r.roughnessWeight,
                r.waterWeight, r.deepOceanExtra, r.deepWaterFrac, r.mountainBiomeWeight,
                r.estimateUncertainty, r.cultureBiomeWeight, r.blockedCost, r.coarseSimplifyBlocks,
                1.0 / Math.max(1.0, roadDetourRatio(mounted)),
                r.enableBoatRoutes ? r.boatCostFactor : 0.0, r.boatEmbarkCost, r.boatWaterFrac,
                r.boatMinCrossingBlocks);
    }

    /** How much longer a road may be than walking straight and still be taken, for this body. */
    public static double roadDetourRatio(boolean mounted) {
        TravellersConfig.Routing r = TravellersConfig.ROUTING;
        return mounted ? r.roadMaxDetourRatioMounted : r.roadMaxDetourRatio;
    }

    /** Test constructor with boats. */
    public static RouteCostParams withBoats(RouteCostParams p, double boatCostFactor, double boatEmbarkCost,
                                            double boatWaterFrac, double boatMinCrossingBlocks) {
        return new RouteCostParams(p.stepWeight, p.slopeWeight, p.pitWeight, p.roughnessWeight, p.waterWeight,
                p.deepOceanExtra, p.deepWaterFrac, p.mountainBiomeWeight, p.estimateUncertainty,
                p.cultureBiomeWeight, p.blockedCost, p.simplifyBlocks, p.roadCostFactor,
                boatCostFactor, boatEmbarkCost, boatWaterFrac, boatMinCrossingBlocks);
    }

    /** Test constructor. */
    public static RouteCostParams of(double stepWeight, double slopeWeight, double pitWeight,
                                     double roughnessWeight, double waterWeight, double deepOceanExtra,
                                     double deepWaterFrac, double mountainBiomeWeight,
                                     double estimateUncertainty, double cultureBiomeWeight, double blockedCost) {
        return new RouteCostParams(stepWeight, slopeWeight, pitWeight, roughnessWeight, waterWeight,
                deepOceanExtra, deepWaterFrac, mountainBiomeWeight, estimateUncertainty, cultureBiomeWeight,
                blockedCost, 4.0, 1.0 / 1.5, 0.0, 0.0, 0.75, 32.0);
    }
}
