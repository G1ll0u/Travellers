package com.jubitus.travellers.server.map.pyramid;

/**
 * Single packed-key representation for a tile coordinate, shared by the whole
 * worldmap pipeline (storage, network, client cache/render). Replaces the many
 * copy-pasted pack/unpack implementations that used to live in each of those files.
 */
public final class TileKey {
    private TileKey() {}

    public static long pack(int tileX, int tileZ) {
        return (((long) tileX) << 32) ^ (tileZ & 0xFFFFFFFFL);
    }

    public static int unpackX(long key) {
        return (int) (key >> 32);
    }

    public static int unpackZ(long key) {
        return (int) key;
    }

    public static int floorDiv(int a, int b) {
        int r = a / b;
        if ((a ^ b) < 0 && (r * b != a)) r--;
        return r;
    }

    /** World block coordinate -> tile coordinate at the given chunks-per-tile granularity. */
    public static int worldToTile(int worldBlockCoord, int chunksPerTile) {
        int chunkCoord = worldBlockCoord >> 4;
        return floorDiv(chunkCoord, chunksPerTile);
    }
}
