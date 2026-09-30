package com.jubitus.travellers.world.zone.stable;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.world.zone.PlayerZoneRecord;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.UUID;

/**
 * Re-attaches a Stable pool horse to its pool the instant its chunk loads - a direct mirror of
 * {@code MountBondLoadHandler} for bonded mounts. The {@code POOL_ID_TAG} round-trips through
 * ForgeData, so the horse always knows which entry it belongs to; this hook makes containment (the
 * AI-strip + the boundary guard) and the tracked-set attach immediately, instead of waiting for
 * {@code StableCoordinator}'s whole-zone settle gate - which can stall for seconds after a load, or
 * indefinitely on a large zone whose corner chunks keep flickering. The offscreen scan in
 * {@code StablePhysicalReconciler} stays as the backstop for horses that somehow lost their tag.
 */
@Mod.EventBusSubscriber(modid = "travellers")
public final class StableHorseLoadHandler {

    private StableHorseLoadHandler() {
    }

    @SubscribeEvent
    public static void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (event.getWorld().isRemote) return;
        if (!TravellersConfig.STABLES.enabled) return;
        if (!(event.getEntity() instanceof EntityHorse)) return;
        if (!(event.getWorld() instanceof WorldServer)) return;

        EntityHorse horse = (EntityHorse) event.getEntity();
        UUID poolId = StableCoordinator.getPoolTag(horse);
        if (poolId == null) return;

        WorldServer world = (WorldServer) event.getWorld();
        StableCoordinator coord = StableCoordinator.get(world);
        PlayerZoneRecord zone = coord.findZoneForPoolHorse(world, poolId);

        if (zone == null) {
            // The pool or the whole Stable is gone - this is just a normal owned tame horse now.
            StableCoordinator.stripPoolTagFromEntity(horse);
            return;
        }

        StableHorseEntry entry = ((StableZoneData) zone.data).findByPoolId(poolId);
        UUID id = horse.getUniqueID();

        if (entry.liveEntityUUID != null && !entry.liveEntityUUID.equals(id)) {
            Entity other = world.getEntityFromUuid(entry.liveEntityUUID);
            if (other instanceof EntityHorse && !other.isDead) {
                // The entry already has a live horse and this is a second one carrying the same tag:
                // a proven duplicate left over from a past desync. Refuse the join outright.
                event.setCanceled(true);
                return;
            }
        }

        entry.liveEntityUUID = id;
        coord.trackLiveHorse(id);
        StableHorseSync.pushEntryOntoHorse(entry, horse, zone.ownerUUID);
        StableHorseAI.applyContainmentInvariants(horse, zone.center, zone.halfWidth, zone.halfDepth);
    }
}
