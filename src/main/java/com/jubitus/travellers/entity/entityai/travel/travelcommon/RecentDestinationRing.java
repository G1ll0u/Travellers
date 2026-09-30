package com.jubitus.travellers.entity.entityai.travel.travelcommon;

import net.minecraft.util.math.BlockPos;

/**
 * Tiny fixed-size ring of recently chosen destinations (packed XZ), newest first. Shared by the
 * loaded side ({@link TravelCommon}) and the virtual side (VirtualTravellerRecord) so anti-ping-pong
 * behaves identically wherever a destination is picked. Pure functions on {@code long[]}; the owner
 * holds the array.
 */
public final class RecentDestinationRing {

    private RecentDestinationRing() {}

    public static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    public static long pack(BlockPos p) {
        return pack(p.getX(), p.getZ());
    }

    /** Newest first, capped at {@code maxN}, de-duplicated. Returns a new array (never mutates input). */
    public static long[] push(long[] ring, BlockPos dest, int maxN) {
        if (maxN <= 0 || dest == null) return ring == null ? new long[0] : ring;
        long key = pack(dest);
        long[] src = ring == null ? new long[0] : ring;
        int keep = Math.min(src.length, maxN - 1);
        long[] out = new long[1 + keep];
        out[0] = key;
        int w = 1;
        for (int i = 0; i < src.length && w < out.length; i++) {
            if (src[i] == key) continue;
            out[w++] = src[i];
        }
        if (w == out.length) return out;
        long[] trimmed = new long[w];
        System.arraycopy(out, 0, trimmed, 0, w);
        return trimmed;
    }

    /** Position in the ring (0 = most recent) or -1 if absent. */
    public static int indexOf(long[] ring, BlockPos dest) {
        if (ring == null || dest == null) return -1;
        long key = pack(dest);
        for (int i = 0; i < ring.length; i++) {
            if (ring[i] == key) return i;
        }
        return -1;
    }

    /** Flattens to an [x0,z0,x1,z1,...] int array for NBT. */
    public static int[] toIntPairs(long[] ring) {
        if (ring == null || ring.length == 0) return new int[0];
        int[] out = new int[ring.length * 2];
        for (int i = 0; i < ring.length; i++) {
            out[i * 2] = (int) (ring[i] >> 32);
            out[i * 2 + 1] = (int) ring[i];
        }
        return out;
    }

    public static long[] fromIntPairs(int[] pairs) {
        if (pairs == null || pairs.length < 2) return new long[0];
        long[] out = new long[pairs.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = pack(pairs[i * 2], pairs[i * 2 + 1]);
        }
        return out;
    }
}
