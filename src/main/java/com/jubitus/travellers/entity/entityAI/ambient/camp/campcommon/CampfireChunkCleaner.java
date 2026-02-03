package com.jubitus.travellers.entity.entityAI.ambient.camp.campcommon;

import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber
public final class CampfireChunkCleaner {

    @SubscribeEvent
    public static void onChunkLoad(net.minecraftforge.event.world.ChunkEvent.Load e) {
        if (!(e.getWorld() instanceof WorldServer)) return;
        WorldServer ws = (WorldServer) e.getWorld();

        // just touching the manager ensures data is loaded; tickAll will handle removal next tick anyway
        // If you want immediate removal, you can call a new mgr.cleanupChunk(ws, e.getChunk().x, e.getChunk().z)
        CampfireManager.get(ws);
    }
}
