package com.jubitus.travellers.village;

import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.Building;
import org.millenaire.common.world.MillWorldData;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads Millénaire's inter-village relation value (-100..+100, see {@link Building} RELATION_*
 * constants) between two settlement centres. Server thread only.
 *
 * <p>Returns {@code null} when either town hall is not currently resident in {@link MillWorldData}
 * or when the two villages have no recorded relation - callers treat {@code null} as neutral (0),
 * never as "excluded".
 *
 * <p>Bounded by settlement <b>pairs</b>, not by traveller count: an access-order {@link LinkedHashMap}
 * with a short TTL ({@code DESTINATIONS.relationCacheTtlTicks}).
 */
public final class MillRelations {

    private static final int MAX_CACHE = 4096;

    private static final class Entry {
        @Nullable final Integer value;
        final long expiryTick;

        Entry(@Nullable Integer value, long expiryTick) {
            this.value = value;
            this.expiryTick = expiryTick;
        }
    }

    private static final Map<Long, Entry> CACHE =
            new LinkedHashMap<Long, Entry>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Entry> eldest) {
                    return size() > MAX_CACHE;
                }
            };

    private MillRelations() {}

    /**
     * @return the relation value home -> dest, or {@code null} (neutral) when unknown/not loaded.
     */
    @Nullable
    public static Integer getRelation(World world, BlockPos homeCentre, BlockPos destCentre, long now) {
        if (homeCentre == null || destCentre == null) return null;
        if (homeCentre.getX() == destCentre.getX() && homeCentre.getZ() == destCentre.getZ()) return null;

        long key = pairKey(homeCentre, destCentre);
        synchronized (CACHE) {
            Entry e = CACHE.get(key);
            if (e != null && now < e.expiryTick) return e.value;
        }

        Integer resolved = resolve(world, homeCentre, destCentre);
        long ttl = Math.max(1, TravellersConfig.DESTINATIONS.relationCacheTtlTicks);
        synchronized (CACHE) {
            CACHE.put(key, new Entry(resolved, now + ttl));
        }
        return resolved;
    }

    @Nullable
    private static Integer resolve(World world, BlockPos homeCentre, BlockPos destCentre) {
        Building homeTh = MillSettlements.townHall(world, homeCentre);
        Building destTh = MillSettlements.townHall(world, destCentre);
        if (homeTh == null || destTh == null) return null;

        Point destPos = destTh.getPos();
        Map<Point, Integer> relations = homeTh.getRelations();
        if (relations == null) return null;
        Integer v = relations.get(destPos);
        return v; // null == no recorded relation -> neutral
    }

    private static long pairKey(BlockPos a, BlockPos b) {
        return mix(((long) a.getX() << 32) ^ (a.getZ() & 0xFFFFFFFFL)) * 31L
                + mix(((long) b.getX() << 32) ^ (b.getZ() & 0xFFFFFFFFL));
    }

    /** splitmix64 finalizer - spreads coordinate bits so the pair key rarely collides. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }
}
