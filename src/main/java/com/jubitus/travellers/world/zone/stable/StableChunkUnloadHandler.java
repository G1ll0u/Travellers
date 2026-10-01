package com.jubitus.travellers.world.zone.stable;

import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ClassInheritanceMultiMap;
import net.minecraft.entity.Entity;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * A pool horse never goes into a chunk file: when its chunk unloads it becomes data on its entry
 * right here, before vanilla saves the chunk (same timing as {@code ChunkUnloadVirtualizeHandler} -
 * a dead entity is not written). At shutdown horses are left to vanilla: the chunk and the pool are
 * saved together, the entry still links the horse, and it comes back with its chunk.
 */
@Mod.EventBusSubscriber(modid = "travellers")
public final class StableChunkUnloadHandler {

    private StableChunkUnloadHandler() {
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getWorld() instanceof WorldServer)) return;
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null || !server.isServerRunning()) return;
        Chunk chunk = event.getChunk();
        if (chunk == null) return;

        List<EntityHorse> found = null;
        for (ClassInheritanceMultiMap<Entity> bucket : chunk.getEntityLists()) {
            for (EntityHorse h : bucket.getByClass(EntityHorse.class)) {
                if (h.isDead || !StableCoordinator.isPoolHorse(h)) continue;
                if (found == null) found = new ArrayList<>();
                found.add(h);
            }
        }
        if (found == null) return;
        WorldServer world = (WorldServer) event.getWorld();
        StableCoordinator coord = StableCoordinator.get(world);
        for (EntityHorse h : found) coord.onPoolHorseChunkUnload(world, h);
    }
}
