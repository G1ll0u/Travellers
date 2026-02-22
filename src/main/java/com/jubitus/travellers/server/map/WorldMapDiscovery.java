package com.jubitus.travellers.server.map;

import java.util.HashSet;
import java.util.Set;

public class WorldMapDiscovery {
    // clé = tuileX,tuileZ packée en long
    private final Set<Long> discoveredTiles = new HashSet<>();

    public static final int CHUNKS_PER_TILE = 16; // 16 chunks = 256 blocks par tuile

    public boolean isDiscovered(int tileX, int tileZ) {
        return discoveredTiles.contains(pack(tileX, tileZ));
    }

    public void setDiscovered(int tileX, int tileZ) {
        discoveredTiles.add(pack(tileX, tileZ));
    }

    private static long pack(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
    }

    public Set<Long> getAll() { return discoveredTiles; }

    // TODO: write/read NBT (pour sauvegarder)
}
