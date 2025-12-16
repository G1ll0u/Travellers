package com.jubitus.travellers.utils.world.pathing.chunkmanager;

import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager;

import java.util.List;

public class TravellerChunkLoadingCallback implements ForgeChunkManager.LoadingCallback {

    @Override
    public void ticketsLoaded(List<ForgeChunkManager.Ticket> tickets, World world) {
        // For simple “follow entity” chunkloading we usually don't restore tickets from disk;
        // just release them so Forge doesn't keep old tickets around on world load.
        for (ForgeChunkManager.Ticket t : tickets) {
            ForgeChunkManager.releaseTicket(t);
        }
    }
}
