package com.jubitus.travellers.worldmap;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.worldmap.pyramid.MapDiscoverySavedData;
import com.jubitus.travellers.worldmap.pyramid.MapPyramidStore;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.UUID;

/** Whether a player knows a settlement: its centre is revealed on their map, or every village is revealed by config. */
public final class KnownVillages {

    private KnownVillages() {}

    public static boolean knows(World world, UUID playerId, BlockPos center) {
        if (TravellersConfig.WORLDMAP.revealAllVillages || !MapPyramidStore.isMapped(world)) return true;
        return MapDiscoverySavedData.get(world).isDiscovered(playerId, center.getX() >> 4, center.getZ() >> 4);
    }
}
