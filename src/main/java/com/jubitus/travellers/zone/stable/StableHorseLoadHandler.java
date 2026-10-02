package com.jubitus.travellers.zone.stable;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.UUID;

/**
 * The only door a pool horse enters the world through: accepted only if its entry links exactly this
 * entity (a spawn by {@link StableMaterializer}, or the linked horse coming back with its chunk). Any
 * other copy - an old chunk file while the entry holds the horse as data or elsewhere - is refused.
 * A pool horse whose Stable is gone becomes an ordinary horse.
 */
@Mod.EventBusSubscriber(modid = "travellers")
public final class StableHorseLoadHandler {

    private StableHorseLoadHandler() {
    }

    @SubscribeEvent
    public static void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (event.getWorld().isRemote) return;
        if (!(event.getEntity() instanceof EntityHorse)) return;
        if (!(event.getWorld() instanceof WorldServer)) return;

        EntityHorse horse = (EntityHorse) event.getEntity();
        UUID poolId = StableCoordinator.getPoolTag(horse);
        if (poolId == null) return;

        WorldServer world = (WorldServer) event.getWorld();
        StableCoordinator coord = StableCoordinator.get(world);
        PlayerZoneRecord zone = coord.findZoneForPoolHorse(world, poolId);
        if (zone == null) {
            StableCoordinator.makeOrdinary(horse);
            return;
        }

        StableHorseEntry entry = ((StableZoneData) zone.data).findByPoolId(poolId);
        if (!horse.getUniqueID().equals(entry.liveEntityUUID)) {
            event.setCanceled(true);
            horse.setDead();
            return;
        }
        StableCoordinator.sanitizeLive(horse);
        StableHorseSync.pushEntryOntoHorse(entry, horse, zone.ownerUUID);
        StableHorseAI.applyContainmentInvariants(horse, zone.center, zone.halfWidth, zone.halfDepth);
    }
}
