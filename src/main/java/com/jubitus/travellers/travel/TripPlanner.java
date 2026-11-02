package com.jubitus.travellers.travel;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.restspot.RestSpotManager;
import com.jubitus.travellers.restspot.RestSpotRecord;
import com.jubitus.travellers.travel.destination.TravelDestinationChooser;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.village.MillSettlements;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

/**
 * The one trip rule, loaded and virtual alike: which stops lie between where a unit leaves from and
 * the destination it chose. A worker stops at every village on the way; a traveller or raider
 * sometimes stops at one of them (its hop preference), more rarely at all of them. Now and then a
 * rest spot near the way is added for a break.
 */
public final class TripPlanner {

    private static final float ALL_MIDS_CHANCE = 0.20f;
    private static final float SKIP_FIRST_MID_CHANCE = 0.25f;

    private TripPlanner() {
    }

    /**
     * The ordered stops, destination last. {@code bad} marks settlements this unit must not stop at (may
     * be null); {@code restStops} is whether this unit takes breaks (it stays at the places it stops at).
     */
    public static List<BlockPos> stops(World world, BlockPos origin, BlockPos dest, boolean worker, boolean raider,
                                       @Nullable BlockPos homeVillage, float hopPreference, Random rng,
                                       @Nullable Predicate<BlockPos> bad, boolean restStops) {
        List<BlockPos> mids = midsOnCorridor(world, origin, dest, TravellersConfig.TRAVEL.corridorWidth, bad);
        mids = TravelDestinationChooser.filterOpenConflictMids(world, homeVillage, raider, mids);

        List<BlockPos> stops = new ArrayList<>();
        if (worker) {
            stops.addAll(mids);
        } else if (!mids.isEmpty() && rng.nextFloat() < hopPreference) {
            int idx = 0;
            if (mids.size() >= 3 && rng.nextFloat() < SKIP_FIRST_MID_CHANCE) {
                idx = 1 + rng.nextInt(Math.min(2, mids.size() - 1));
            }
            stops.add(mids.get(idx));
        } else if (!mids.isEmpty() && rng.nextFloat() < ALL_MIDS_CHANCE) {
            stops.addAll(mids);
        }
        if (!dest.equals(origin)) stops.add(dest);
        TravellersConfig.RestSpots rs = TravellersConfig.REST_SPOTS;
        if (restStops && rs.enabled && world instanceof WorldServer && !stops.isEmpty() && rng.nextDouble() < rs.stopChance) {
            addRestSpot((WorldServer) world, origin, stops, raider, rng);
        }
        return stops;
    }

    /** Inserts one rest spot lying near a leg of the trip, prettier ones preferred, never one the other side holds. */
    private static void addRestSpot(WorldServer world, BlockPos origin, List<BlockPos> stops, boolean raider, Random rng) {
        TravellersConfig.RestSpots rs = TravellersConfig.REST_SPOTS;
        double reach2 = (double) rs.maxDetour * rs.maxDetour;
        List<RestSpotRecord> picks = new ArrayList<>();
        List<Integer> legs = new ArrayList<>();
        double total = 0;
        BlockPos from = origin;
        for (int leg = 0; leg < stops.size(); leg++) {
            BlockPos to = stops.get(leg);
            Vec3d a = new Vec3d(from.getX() + 0.5, 0, from.getZ() + 0.5);
            Vec3d b = new Vec3d(to.getX() + 0.5, 0, to.getZ() + 0.5);
            Vec3d ab = b.subtract(a);
            double ab2 = ab.lengthSquared();
            for (RestSpotRecord spot : RestSpotManager.get(world).getAll()) {
                Vec3d p = new Vec3d(spot.center.getX() + 0.5, 0, spot.center.getZ() + 0.5);
                double t = ab2 < 1e-6 ? 0 : ab.dotProduct(p.subtract(a)) / ab2;
                if (t <= 0.05 || t >= 0.95) continue;
                if (EntityTraveller.pointToSegment2DSq(p, a, b) > reach2) continue;
                // A village may have grown next to it since it was found.
                if (MillSettlements.isNear(world, spot.center, rs.minVillageDistance)) continue;
                picks.add(spot);
                legs.add(leg);
                total += Math.max(1, spot.score());
            }
            from = to;
        }
        if (picks.isEmpty()) return;
        java.util.Map<Long, Integer> sides = RestSpotManager.sidesStaying(world);
        for (int i = picks.size() - 1; i >= 0; i--) {
            if (!RestSpotManager.heldByOtherSide(sides, picks.get(i).center, raider)) continue;
            total -= Math.max(1, picks.get(i).score());
            picks.remove(i);
            legs.remove(i);
        }
        if (picks.isEmpty()) return;
        double roll = rng.nextDouble() * total;
        for (int i = 0; i < picks.size(); i++) {
            roll -= Math.max(1, picks.get(i).score());
            if (roll <= 0 || i == picks.size() - 1) {
                stops.add(legs.get(i), picks.get(i).center);
                return;
            }
        }
    }

    /** Settlements near the straight line origin → dest, strictly between them, in travel order. */
    private static List<BlockPos> midsOnCorridor(World world, BlockPos origin, BlockPos dest, double corridorWidth,
                                                 @Nullable Predicate<BlockPos> bad) {
        List<BlockPos> mids = new ArrayList<>();
        Vec3d a = new Vec3d(origin.getX() + 0.5, 0, origin.getZ() + 0.5);
        Vec3d b = new Vec3d(dest.getX() + 0.5, 0, dest.getZ() + 0.5);
        Vec3d ab = b.subtract(a);
        double ab2 = ab.lengthSquared();
        if (ab2 < 1e-6) return mids;

        List<double[]> found = new ArrayList<>(); // {along-track fraction, index}
        List<BlockPos> centers = MillSettlements.snapshot(world).centers;
        for (int i = 0; i < centers.size(); i++) {
            BlockPos v = centers.get(i);
            if (v.equals(origin) || v.equals(dest) || (bad != null && bad.test(v))) continue;
            Vec3d p = new Vec3d(v.getX() + 0.5, 0, v.getZ() + 0.5);
            double t = ab.dotProduct(p.subtract(a)) / ab2;
            if (t <= 0.02 || t >= 0.98) continue;
            if (EntityTraveller.pointToSegment2DSq(p, a, b) <= corridorWidth * corridorWidth) {
                found.add(new double[]{t, i});
            }
        }
        found.sort((x, y) -> Double.compare(x[0], y[0]));
        for (double[] f : found) mids.add(centers.get((int) f[1]));
        return mids;
    }
}
