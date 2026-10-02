package com.jubitus.travellers.world.virtualization;

import com.jubitus.travellers.mount.OrphanMountCleanup;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityHorse;
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
 * the chunk leaves the world. Also the one place a bonded or orphaned horse can be reconciled/removed
 * with zero chance of a player noticing - see the horse-handling block below.
 *
 * <p>This is what makes "loaded chunk = real entity, unloaded chunk = virtual record" an invariant
 * rather than a race. The polling side ({@link VirtualTravellerManager#checkVirtualize}) cannot cover
 * this case on its own: {@code PlayerChunkMap.removeEntry()} stops tracking a chunk and queues its
 * unload in the same call, and {@code ChunkProviderServer.tick()} then unloads up to 100 queued chunks
 * on the very next tick - far sooner than any debounced poll can notice. Without this handler a unit
 * whose chunk unloads is swallowed by vanilla's {@code Chunk.onUnload()} and written into the chunk
 * file, ending up neither loaded nor virtual: it stops travelling, drops off the worldmap, stops being
 * counted by the population controller, and reappears frozen in place whenever that chunk next loads.
 * A bonded horse whose own chunk unloads on a different tick than its rider's was the exact same trap
 * from the mount side - silently swallowed into the chunk file, unrecoverable (see
 * {@code TravellerMountSupport.handleBondedHorseChunkUnload}'s doc) - and an unclaimed loose ex-mount
 * needs the same "nobody is watching right now" moment to be removed without ever popping visibly.
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

        List<EntityTraveller> foundTravellers = null;
        List<EntityHorse> foundHorses = null;
        for (ClassInheritanceMultiMap<Entity> bucket : chunk.getEntityLists()) {
            for (EntityTraveller t : bucket.getByClass(EntityTraveller.class)) {
                if (t.isDead) continue;
                if (foundTravellers == null) foundTravellers = new ArrayList<>();
                foundTravellers.add(t);
            }
            // A bonded horse whose own chunk unloads on a different tick than its rider's is the one
            // gap that otherwise turns it into a permanently abandoned horse (see
            // TravellerMountSupport.handleBondedHorseChunkUnload's doc) - and an already-orphaned one
            // (rider died with nowhere to send it) needs the same "only vanish where nobody can see it"
            // moment OrphanMountCleanup relies on. Both are handled below, after the travellers.
            for (EntityHorse h : bucket.getByClass(EntityHorse.class)) {
                if (h.isDead) continue;
                if (!TravellerMountSupport.isBondedMount(h) && !OrphanMountCleanup.isOrphaned(h)) continue;
                if (foundHorses == null) foundHorses = new ArrayList<>();
                foundHorses.add(h);
            }
        }
        if (foundTravellers == null && foundHorses == null) return;

        // Collected first: virtualizing/killing an entity mutates the very chunk entity lists being
        // walked above.
        if (foundTravellers != null) {
            VirtualTravellerManager mgr = VirtualTravellerManager.get(world);
            for (EntityTraveller t : foundTravellers) {
                VirtualizationLog.virtualize("chunk unload", t);
                if (t.forceVirtualizeResolvingBlockers()) continue;
                // Refused. When a virtual record already owns this identity, this body is the
                // duplicate (see TravellerLoadDedupHandler) and letting the chunk serialize it brings
                // it back on every single load. Kill it here instead - the record is authoritative and
                // materializes the unit properly. Any other refusal is legitimate (debug path test, a
                // worker awaiting pickup) and is left alone.
                if (mgr.isVirtual(t.getUniqueID())) t.setDead();
            }
        }
        if (foundHorses != null) {
            long now = world.getTotalWorldTime();
            for (EntityHorse h : foundHorses) {
                // A horse ridden by (or bonded to) a rider virtualized just above is already dead -
                // captureMountIfBonded already reconciled it, nothing left to do here.
                if (h.isDead) continue;
                if (TravellerMountSupport.isBondedMount(h)) {
                    TravellerMountSupport.handleBondedHorseChunkUnload(world, h);
                } else if (OrphanMountCleanup.isEligibleAtChunkUnload(h, now)) {
                    h.setDead();
                }
            }
        }
    }

    private static boolean isServerRunning() {
        net.minecraft.server.MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        return server != null && server.isServerRunning();
    }
}
