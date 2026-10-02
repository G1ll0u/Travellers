package com.jubitus.travellers.pathing.terrain;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Lifecycle hooks for the terrain profile grid, mirroring
 * {@code worldmap/pyramid/MapPyramidPersistenceEvents}: create per-dimension state on world load,
 * flush dirty regions asynchronously on every save-all, one final synchronous flush on unload.
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class TerrainPersistenceEvents {

    private TerrainPersistenceEvents() {
    }

    @SubscribeEvent
    public static void onWorldLoad(WorldEvent.Load e) {
        World world = e.getWorld();
        if (world.isRemote || !(world instanceof WorldServer)) return;
        if (!TravellersConfig.ROUTING.enablePlannedRoutes) return;
        TerrainWorldState.get((WorldServer) world);
    }

    @SubscribeEvent
    public static void onWorldSave(WorldEvent.Save e) {
        World world = e.getWorld();
        if (world.isRemote || !(world instanceof WorldServer)) return;
        TerrainWorldState state = TerrainWorldState.getExisting(world.provider.getDimension());
        if (state != null) state.flushDirtyAsync(world.getTotalWorldTime());
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload e) {
        World world = e.getWorld();
        if (world.isRemote || !(world instanceof WorldServer)) return;
        TerrainWorldState.remove(world.provider.getDimension());
    }
}
