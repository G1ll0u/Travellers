package com.jubitus.travellers.utils.world.camp;

import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Feeds the campsite discovery queue on chunk LOAD (not just generation), so pregenerated worlds
 * are probed as the player travels through them. A dedup set in CampsiteManager ensures each chunk
 * is probed at most once per session, and the actual probe runs in CampsiteManager.tick against
 * already-loaded terrain, so this never triggers generation itself.
 */
public class CampsiteDiscoveryHandler {

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!TravellersConfig.CAMP_EVENTS.enabled) return;
        if (event.getWorld().isRemote) return;
        if (!(event.getWorld() instanceof WorldServer)) return;
        CampsiteManager.get((WorldServer) event.getWorld())
                .enqueueChunk(event.getChunk().x, event.getChunk().z);
    }
}
