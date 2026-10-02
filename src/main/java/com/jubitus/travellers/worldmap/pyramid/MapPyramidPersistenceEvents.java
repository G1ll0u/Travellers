package com.jubitus.travellers.worldmap.pyramid;

import com.jubitus.travellers.Tags;
import net.minecraft.world.World;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Worldmap tile persistence hooks. {@code WorldEvent.Save} fires on every vanilla save-all
 * (manual {@code /save-all} and the periodic autosave) — dirty pyramid levels are flushed to
 * disk asynchronously there (see {@link MapPyramidStore}), so this never adds to the
 * already-blocking vanilla chunk/NBT save on the server thread. {@code WorldEvent.Unload} does
 * one final synchronous flush per dimension so a clean shutdown never loses data.
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public class MapPyramidPersistenceEvents {

    @SubscribeEvent
    public static void onWorldSave(WorldEvent.Save e) {
        World world = e.getWorld();
        if (world.isRemote) return;
        MapPyramidStore.saveAllDirtyAsync(world);
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload e) {
        World world = e.getWorld();
        if (world.isRemote) return;
        MapPyramidStore.flushAllSync(world);
    }
}
