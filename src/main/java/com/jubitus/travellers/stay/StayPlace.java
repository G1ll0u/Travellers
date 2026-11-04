package com.jubitus.travellers.stay;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.ground.SeatFinder;
import com.jubitus.travellers.restspot.RestSpotManager;
import com.jubitus.travellers.restspot.RestSpotRecord;
import com.jubitus.travellers.village.VillageVisitIndex;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Where a unit stays: the one answer to "is this trip stop a place to stay, and what is it like".
 * The stay itself (walking, talking, horse, group) is the same everywhere; only what is listed here
 * depends on the place: a Millénaire settlement or a rest spot. Village-only activities (buildings,
 * panels) read {@link #settlement()}.
 */
public final class StayPlace {

    public final BlockPos center;
    /** How far the place reaches: followers disperse inside it, a horse is tied at its edge. */
    public final int radius;
    @Nullable
    private final VillageVisitIndex.Settlement settlement;
    @Nullable
    private final RestSpotRecord restSpot;

    private StayPlace(BlockPos center, int radius, @Nullable VillageVisitIndex.Settlement settlement,
                      @Nullable RestSpotRecord restSpot) {
        this.center = center;
        this.radius = radius;
        this.settlement = settlement;
        this.restSpot = restSpot;
    }

    /** The place a trip stop stands for, or null when the stop is no place to stay. */
    @Nullable
    public static StayPlace at(World world, BlockPos stop) {
        VillageVisitIndex.Settlement s = VillageVisitIndex.get(world, stop);
        if (s != null) return new StayPlace(s.center, Math.max(s.radius, TravellersConfig.VILLAGE_VISIT.arrivalRadius), s, null);
        if (!TravellersConfig.REST_SPOTS.enabled || !(world instanceof WorldServer)) return null;
        RestSpotRecord spot = RestSpotManager.get((WorldServer) world).at(stop);
        return spot == null ? null : new StayPlace(spot.center, RestSpotRecord.REACH, null, spot);
    }

    /** The village's own data, or null for a place that is not a settlement. */
    @Nullable
    public VillageVisitIndex.Settlement settlement() {
        return settlement;
    }

    /** A break at a rest spot: nothing to visit, no village to give to. */
    public boolean isRestSpot() {
        return restSpot != null;
    }

    /** Where to sit down with a view, best first; empty for a village or a spot whose seats aren't known yet. */
    public List<SeatFinder.Seat> seats(World world) {
        if (restSpot == null || !(world instanceof WorldServer)) return Collections.emptyList();
        return RestSpotManager.get((WorldServer) world).seatsOf((WorldServer) world, restSpot);
    }

    /** A lone Millénaire building: a short halt in front of it rather than a stay. */
    public boolean isLone() {
        return settlement != null && settlement.lone;
    }

    public int arrivalRadius() {
        if (restSpot != null) return RestSpotRecord.ARRIVAL_RADIUS;
        TravellersConfig.VillageVisit cfg = TravellersConfig.VILLAGE_VISIT;
        return isLone() ? cfg.loneBuildingArrivalRadius : cfg.arrivalRadius;
    }

    public int rollStayTicks(Random rng) {
        if (restSpot != null) {
            TravellersConfig.RestSpots rs = TravellersConfig.REST_SPOTS;
            int max = Math.max(rs.pauseMinMinutes, rs.pauseMaxMinutes);
            return 20 * 60 * rs.pauseMinMinutes + rng.nextInt(20 * 60 * (max - rs.pauseMinMinutes) + 1);
        }
        TravellersConfig.VillageVisit cfg = TravellersConfig.VILLAGE_VISIT;
        int min = isLone() ? cfg.loneBuildingHaltMinSeconds : cfg.stayMinSeconds;
        int max = Math.max(min, isLone() ? cfg.loneBuildingHaltMaxSeconds : cfg.stayMaxSeconds);
        return 20 * (min + rng.nextInt(max - min + 1));
    }

    /** Points a stroll starts from (a random spot around one of them), and posts a horse may be moved to. */
    public List<BlockPos> anchors() {
        return settlement != null ? settlement.entrances : Collections.singletonList(center);
    }

    public int strollSpread() {
        return restSpot != null ? RestSpotRecord.REACH - 4 : TravellersConfig.VILLAGE_VISIT.strollSpreadBlocks;
    }

    /** What is worth a look while standing around. */
    public List<BlockPos> placesOfInterest() {
        if (settlement == null || settlement.buildings.isEmpty()) return Collections.emptyList();
        List<BlockPos> out = new ArrayList<>(settlement.buildings.size());
        for (VillageVisitIndex.Target t : settlement.buildings) out.add(t.entrance != null ? t.entrance : t.point);
        return out;
    }

    public String describe() {
        if (restSpot != null) return "rest spot score=" + restSpot.score();
        if (settlement == null) return "place";
        return (settlement.lone ? "lone" : "village") + " entrances=" + settlement.entrances.size()
                + " buildings=" + settlement.buildings.size() + " panels=" + settlement.panels.size();
    }

    /** XZ distance check against the place's reach. */
    public boolean contains(double x, double z) {
        double dx = x - (center.getX() + 0.5D);
        double dz = z - (center.getZ() + 0.5D);
        return dx * dx + dz * dz <= (double) radius * radius;
    }
}
