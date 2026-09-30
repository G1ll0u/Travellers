package com.jubitus.travellers.world.mill;

import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.util.List;

public class VillageDataLoader {
    /**
     * Kept for compatibility — now delegates to MillenaireVillageDirectory.
     */
    public static List<BlockPos> loadVillageCenters(File millenaireFolder) {
        MillenaireVillageDirectory.reloadIfStale(millenaireFolder);
        return MillenaireVillageDirectory.getAllCenters();
    }
}


