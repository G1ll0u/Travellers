package com.jubitus.travellers.travel;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.travel.destination.TravelDestinationChooser;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.village.MillSettlements;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

/**
 * The one trip rule, loaded and virtual alike: which stops lie between where a unit leaves from and
 * the destination it chose. A worker stops at every village on the way; a traveller or raider
 * sometimes stops at one of them (its hop preference), more rarely at all of them.
 */
public final class TripPlanner {

    private static final float ALL_MIDS_CHANCE = 0.20f;
    private static final float SKIP_FIRST_MID_CHANCE = 0.25f;

    private TripPlanner() {
    }

    /** The ordered stops, destination last. {@code bad} marks settlements this unit must not stop at (may be null). */
    public static List<BlockPos> stops(World world, BlockPos origin, BlockPos dest, boolean worker, boolean raider,
                                       @Nullable BlockPos homeVillage, float hopPreference, Random rng,
                                       @Nullable Predicate<BlockPos> bad) {
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
        return stops;
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
