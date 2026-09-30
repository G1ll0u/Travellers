package com.jubitus.travellers.world.pathing.route;

/**
 * Pure cost formulas for the route search. No world access. Distance/step cost is added by the
 * search itself and must dominate the sum of these terrain terms for a typical leg, or units take
 * absurd detours.
 */
public final class TravelCostModel {

    /** A candidate below its reference by at least this many blocks may count toward a pit. */
    public static final int PIT_DROP = 6;

    /** Rise multiplier that reproduces the historical cost at the old 64-block lattice spacing. */
    static final double RISE_SCALE = 2.0;

    private TravelCostModel() {
    }

    /**
     * Terrain penalty for traversing one lattice edge, from samples taken every ~16 blocks along it
     * (one per terrain-grid cell it crosses - see {@code CoarseRouteSearch}). Sampling the whole edge
     * rather than only its destination node is what charges a ridge, stream or mountain cell sitting
     * <em>between</em> two widely-spaced lattice nodes instead of letting the search step over it for
     * free.
     *
     * <p>Roughness, water and biome terms are costs <em>per block travelled</em> and scale with the
     * step length via {@code distScale}; the slope term is per block <em>climbed</em> and does not.
     *
     * @param summedRise   sum of |Δ mean-Y| between consecutive samples along the edge (total
     *                     vertical travel - a mid-edge ridge is charged for both the climb and the drop)
     * @param maxAmplitude worst 0..255 roughness of any sample on the edge
     * @param maxWater     worst 0..255 water fraction of any sample on the edge
     * @param anyMountain  true if any sample sits in a MOUNTAIN-type biome
     * @param anyEstimate  true if any sample is only biome-estimated, not proven real
     */
    public static double edgeTerrainCost(double summedRise, int maxAmplitude, int maxWater,
                                         boolean anyMountain, boolean anyEstimate,
                                         double distScale, RouteCostParams p) {
        double c = 0.0;

        // Climbing is paid per block of height, whatever the lattice spacing: scaling it with the step
        // length made a ridge cheaper the finer the grid. RISE_SCALE keeps the old 64-block tuning.
        c += summedRise * p.slopeWeight * RISE_SCALE;
        c += maxAmplitude * p.roughnessWeight * distScale;

        double wf = maxWater / 255.0;
        c += wf * p.waterWeight * distScale;
        if (wf >= p.deepWaterFrac) c += p.deepOceanExtra * distScale;

        if (anyMountain) c += p.mountainBiomeWeight * distScale;

        // Per-edge nudge toward proven-real corridors, not a per-block cost: left unscaled.
        if (anyEstimate) c += p.estimateUncertainty;
        // Culture-biome friendliness (Plan B) would add here; zero until then.

        return c;
    }

    /**
     * Extra cost for stepping down into a pit: the candidate sits well below where we came from
     * <em>and</em> below most of its own lattice ring.
     *
     * @param cellHeight  candidate cell height
     * @param refHeight   height of the cell we came from
     * @param ringHeights the 8 lattice-neighbour heights of the candidate ({@code Short.MIN_VALUE} = unknown)
     */
    public static double pitExtra(int cellHeight, int refHeight, short[] ringHeights,
                                  double distScale, RouteCostParams p) {
        if (cellHeight == Short.MIN_VALUE || refHeight == Short.MIN_VALUE) return 0.0;
        if (cellHeight >= refHeight - PIT_DROP) return 0.0;
        int valid = 0;
        int higher = 0;
        for (short h : ringHeights) {
            if (h == Short.MIN_VALUE) continue;
            valid++;
            if (h > cellHeight + PIT_DROP) higher++;
        }
        return (valid >= 5 && higher >= valid - 1) ? p.pitWeight * distScale : 0.0;
    }
}
