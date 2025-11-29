package com.jubitus.traveller.traveller.utils.villages;

import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.io.File;

@Mod.EventBusSubscriber
public final class MillenaireHooks {

    @SubscribeEvent
    public static void onWorldLoad(net.minecraftforge.event.world.WorldEvent.Load e) {
        if (e.getWorld().isRemote) return;
        WorldServer ws = (WorldServer) e.getWorld();

        File worldDir = ws.getSaveHandler().getWorldDirectory();
        File millFolder = new File(worldDir, "millenaire"); // this is where villages.txt/lonebuildings.txt usually are

        MillenaireVillageDirectory.reloadIfStale(millFolder);
    }
}
