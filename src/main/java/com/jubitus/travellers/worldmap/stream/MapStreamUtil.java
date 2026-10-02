package com.jubitus.travellers.worldmap.stream;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Shared filter/cap logic for the map debug streams (population, camps, ...): keep only entries in
 * the requested dimension and range, and if there are still more than {@code cap} (e.g. thousands of
 * travellers/camp objects clustered in view), keep the nearest ones instead of an arbitrary prefix.
 * A naive "stop once the cap is reached while scanning" truncation can silently drop nearby points
 * in favor of farther ones purely based on iteration order - this makes truncation, when it happens,
 * always favor what's closest to the viewed area.
 */
final class MapStreamUtil {

    private MapStreamUtil() {}

    static <T> List<T> nearestWithinRange(List<T> all, int dim, ToIntFunction<T> dimOf, Function<T, BlockPos> posOf,
                                           int cx, int cz, long rangeSq, int cap) {
        List<T> matched = new ArrayList<>();
        for (T t : all) {
            if (dimOf.applyAsInt(t) != dim) continue;
            BlockPos p = posOf.apply(t);
            long dx = (long) p.getX() - cx;
            long dz = (long) p.getZ() - cz;
            if (dx * dx + dz * dz <= rangeSq) matched.add(t);
        }
        if (matched.size() > cap) {
            matched.sort(Comparator.comparingLong(t -> distSq(posOf.apply(t), cx, cz)));
            matched = new ArrayList<>(matched.subList(0, cap));
        }
        return matched;
    }

    private static long distSq(BlockPos p, int cx, int cz) {
        long dx = (long) p.getX() - cx;
        long dz = (long) p.getZ() - cz;
        return dx * dx + dz * dz;
    }
}
