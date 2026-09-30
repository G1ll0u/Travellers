package com.jubitus.travellers.world.zone.stable;

import com.jubitus.travellers.world.zone.PlayerZoneManager;
import com.jubitus.travellers.world.zone.PlayerZoneRecord;
import com.jubitus.travellers.world.zone.ZoneType;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.UUID;

/**
 * The authoritative "this pool horse is actually gone" signal, mirroring {@code MountDeathHandler}
 * for bonded mounts. Without this, a dead horse's pool entry just goes dormant (liveEntityUUID
 * cleared by the next reconcile pass) while its horseNbt snapshot - now continuously kept fresh by
 * {@code StableCoordinator.reconcilePhysical} - survives, and the materialize-one-dormant-entry-per-pass
 * logic spawns a brand new entity from it: a horse "resurrecting" after death. Removing the entry
 * outright on death is also what keeps a dead horse from counting against the owner's cap forever.
 */
@Mod.EventBusSubscriber(modid = "travellers")
public final class StableHorseDeathHandler {

    private StableHorseDeathHandler() {
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity().world.isRemote) return;
        if (!(event.getEntity() instanceof EntityHorse)) return;
        if (!(event.getEntity().world instanceof WorldServer)) return;

        WorldServer world = (WorldServer) event.getEntity().world;
        UUID id = event.getEntity().getUniqueID();
        if (!StableCoordinator.get(world).isStableTracked(id)) return;

        PlayerZoneManager mgr = PlayerZoneManager.get(world);
        for (PlayerZoneRecord zone : mgr.getAll()) {
            if (zone.type != ZoneType.STABLE || !(zone.data instanceof StableZoneData)) continue;
            StableZoneData data = (StableZoneData) zone.data;
            StableHorseEntry entry = data.findByLiveEntity(id);
            if (entry == null) continue;

            data.pool.remove(entry);
            StableCoordinator.get(world).forgetLiveHorse(id);
            mgr.markDirty();
            StableWatchLog.record(world, zone, StableWatchLog.Kind.LEAVE,
                    StableWatchLog.shortId(entry.poolId) + " died", true);
            return;
        }
    }
}
