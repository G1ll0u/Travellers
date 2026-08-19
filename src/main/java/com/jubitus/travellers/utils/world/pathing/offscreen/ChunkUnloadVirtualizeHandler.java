package com.jubitus.travellers.utils.world.pathing.offscreen;

import com.jubitus.travellers.entity.travellers.EntityTraveller;
import net.minecraft.entity.Entity;
import net.minecraft.util.ClassInheritanceMultiMap;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns every traveller/raider/worker in an unloading chunk into a virtual record, at the exact moment
 * the chunk leaves the world.
 *
 * <p>This is what makes "loaded chunk = real entity, unloaded chunk = virtual record" an invariant
 * rather than a race. The polling side ({@link VirtualTravellerManager#checkVirtualize}) cannot cover
 * this case on its own: {@code PlayerChunkMap.removeEntry()} stops tracking a chunk and queues its
 * unload in the same call, and {@code ChunkProviderServer.tick()} then unloads up to 100 queued chunks
 * on the very next tick - far sooner than any debounced poll can notice. Without this handler a unit
 * whose chunk unloads is swallowed by vanilla's {@code Chunk.onUnload()} and written into the chunk
 * file, ending up neither loaded nor virtual: it stops travelling, drops off the worldmap, stops being
 * counted by the population controller, and reappears frozen in place whenever that chunk next loads.
 *
 * <p>Timing is what makes it safe. {@code Chunk.onUnload()} posts this event as its last action, and
 * {@code ChunkProviderServer} only calls {@code saveChunkData(chunk)} afterwards - so an entity killed
 * here is already dead by the time the chunk is serialized, and {@code Entity.writeToNBTOptional}
 * refuses to write a dead entity. The unit therefore leaves through exactly one door instead of two.
 */
public final class ChunkUnloadVirtualizeHandler {

    private ChunkUnloadVirtualizeHandler() {
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        Chunk chunk = event.getChunk();
        if (chunk == null) return;
        if (!(event.getWorld() instanceof WorldServer)) return;
        WorldServer world = (WorldServer) event.getWorld();
        if (world.isRemote) return;

        // Shutdown: let vanilla save them into their chunks as usual. They reload exactly where they
        // were and virtualize normally during the next session. Mass-virtualizing the entire world here
        // would hinge on the records file still being written afterwards - a bad thing to depend on
        // while the server is already tearing down.
        if (!isServerRunning()) return;

        List<EntityTraveller> found = null;
        for (ClassInheritanceMultiMap<Entity> bucket : chunk.getEntityLists()) {
            for (EntityTraveller t : bucket.getByClass(EntityTraveller.class)) {
                if (t.isDead) continue;
                if (found == null) found = new ArrayList<>();
                found.add(t);
            }
        }
        if (found == null) return;

        // Collected first: virtualizing kills the entity, which mutates the very chunk entity lists
        // being walked above.
        for (EntityTraveller t : found) {
            t.forceVirtualizeResolvingBlockers();
        }
    }

    private static boolean isServerRunning() {
        net.minecraft.server.MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        return server != null && server.isServerRunning();
    }
}
