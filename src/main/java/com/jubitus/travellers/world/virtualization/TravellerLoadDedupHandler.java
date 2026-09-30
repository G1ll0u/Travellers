package com.jubitus.travellers.world.virtualization;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Refuses a traveller/raider/worker entering the world while a virtual record already holds its
 * identity. Mirrors {@code StableHorseLoadHandler} and {@code MountBondLoadHandler}: the moment a
 * body joins is the only place a duplicate can be caught before anything downstream sees two of it.
 *
 * <p>"Loaded entity XOR virtual record" is the invariant the whole offscreen system rests on, and it
 * is enforced at three points: {@link ChunkUnloadVirtualizeHandler} on the way out,
 * {@link VirtualTravellerManager#checkPlayerMaterialize} on the way in, and this handler for the one
 * door neither of them controls - a body read back out of a chunk file.
 *
 * <p>Such a body exists whenever a virtualize did not actually kill the entity: the manager refuses
 * to virtualize onto an identity that already has a record, so a unit in that state is left standing
 * and gets written into its chunk like any ordinary mob. It is a stale snapshot by definition - the
 * record has kept simulating since - so the record wins and the joining copy is dropped.
 *
 * <p>Cancelling alone is not enough: the chunk's own entity list still holds it (the loader adds it
 * there before posting this event), so it would be written back out and retried on every single
 * load. Marking it dead as well makes {@code Entity.writeToNBTOptional} skip it on the next save, so
 * the duplicate is gone for good rather than permanently re-offered.
 *
 * <p>Nothing is lost by dropping it: the record materializes into the same now-loaded chunk within
 * {@code MATERIALIZATION.materializeChunkStableTicks}, carrying the up-to-date state.
 */
@Mod.EventBusSubscriber(modid = "travellers")
public final class TravellerLoadDedupHandler {

    private TravellerLoadDedupHandler() {
    }

    @SubscribeEvent
    public static void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (event.getWorld().isRemote) return;
        if (!(event.getEntity() instanceof EntityTraveller)) return;
        if (!(event.getWorld() instanceof WorldServer)) return;

        EntityTraveller entity = (EntityTraveller) event.getEntity();
        WorldServer world = (WorldServer) event.getWorld();
        if (!VirtualTravellerManager.get(world).isVirtual(entity.getUniqueID())) return;

        event.setCanceled(true);
        entity.setDead();

        if (TravellersConfig.GENERAL.debug) {
            FMLLog.log.info("[Travellers/Virtual] dropped a duplicate {} at ({}, {}) - a virtual "
                            + "record already holds that identity",
                    entity.getName(), (int) entity.posX, (int) entity.posZ);
        }
    }
}
