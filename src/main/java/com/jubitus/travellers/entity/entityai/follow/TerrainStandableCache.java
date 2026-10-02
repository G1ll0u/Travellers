package com.jubitus.travellers.entity.entityai.follow;

import com.jubitus.travellers.ground.Footing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared per-world, per-tick cache for "can a worker stand here" checks. With hundreds of
 * followers packed together, many end up scoring the same or overlapping candidate columns
 * within the same tick — this avoids repeating the same block-state lookups for each of them.
 */
final class TerrainStandableCache {

    private TerrainStandableCache() {
    }

    private static final class WorldCache {
        long tick = Long.MIN_VALUE;
        final Map<Long, Boolean> results = new HashMap<>();
    }

    private static final Map<World, WorldCache> CACHES = new HashMap<>();

    /** {@link Footing#canStandAt}, remembered for the rest of the tick. */
    static boolean isStandable(World world, BlockPos pos) {
        WorldCache cache = CACHES.computeIfAbsent(world, w -> new WorldCache());

        long now = world.getTotalWorldTime();
        if (cache.tick != now) {
            cache.tick = now;
            cache.results.clear();
        }

        long key = pos.toLong();
        Boolean cached = cache.results.get(key);
        if (cached != null) return cached;

        boolean result = Footing.canStandAt(world, pos);
        cache.results.put(key, result);
        return result;
    }
}
