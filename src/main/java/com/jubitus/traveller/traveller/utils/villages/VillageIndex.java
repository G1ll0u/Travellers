package com.jubitus.traveller.traveller.utils.villages;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import java.io.File;

public final class VillageIndex {

    // One cache per dimension
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Cache> BY_DIM =
            new java.util.concurrent.ConcurrentHashMap<>();

    // How often we even look at disk (ms)
    private static final long CHECK_INTERVAL_MS = 2000L; // 2s

    /**
     * Get current village centers for this world. Auto-refreshes when txt files change.
     */
    public static java.util.List<BlockPos> getVillages(World world) {
        if (!(world instanceof WorldServer)) return java.util.Collections.emptyList();
        WorldServer ws = (WorldServer) world;
        final int dim = ws.provider.getDimension();

        Cache cache = BY_DIM.computeIfAbsent(dim, d -> new Cache());

        long now = System.currentTimeMillis();
        if (now - cache.lastCheckMs >= CHECK_INTERVAL_MS) {
            cache.lastCheckMs = now;

            // Ask the directory to reload if villages.txt/lonebuildings.txt changed
            File millFolder = new File(ws.getSaveHandler().getWorldDirectory(), "millenaire");
            MillenaireVillageDirectory.reloadIfStale(millFolder);

            // Pull the latest list of village and lonebuildings centers
            java.util.List<BlockPos> centers = MillenaireVillageDirectory.getAllCenters();

            // Store an unmodifiable copy
            cache.villages = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(centers));
        }
        return cache.villages;
    }

    /**
     * Force a refresh right now (e.g., you know Millénaire wrote files this tick).
     */
    public static void forceRefresh(World world) {
        if (!(world instanceof WorldServer)) return;
        WorldServer ws = (WorldServer) world;
        final int dim = ws.provider.getDimension();

        File millFolder = new File(ws.getSaveHandler().getWorldDirectory(), "millenaire");
        MillenaireVillageDirectory.forceReload(millFolder);

        java.util.List<BlockPos> centers = MillenaireVillageDirectory.getAllCenters();

        Cache cache = BY_DIM.computeIfAbsent(dim, d -> new Cache());
        cache.villages = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(centers));
        cache.lastCheckMs = System.currentTimeMillis();
    }

    /**
     * Clear cache for this world (call on WorldEvent.Unload).
     */
    public static void clear(World world) {
        if (world instanceof WorldServer) {
            BY_DIM.remove(world.provider.getDimension());
        }
    }

    /**
     * Clear everything (e.g., on server stopping).
     */
    public static void clearAll() {
        BY_DIM.clear();
    }

    private static final class Cache {
        volatile java.util.List<BlockPos> villages = java.util.Collections.emptyList();
        volatile long lastCheckMs = 0L;
        // we don’t duplicate mtimes here; MillenaireVillageDirectory tracks its own mtimes
    }
}

