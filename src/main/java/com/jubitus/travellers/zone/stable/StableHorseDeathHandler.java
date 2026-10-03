package com.jubitus.travellers.zone.stable;

import com.jubitus.travellers.horse.HorseMarkers;
import com.jubitus.travellers.zone.PlayerZoneManager;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import com.jubitus.travellers.zone.ZoneType;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.UUID;

/**
 * The authoritative "this pool horse is actually gone" signal, mirroring the end of a mount bond
 * (both dispatched by {@code HorseEvents}): a dead pool horse's entry is removed outright, so it is never respawned from
 * its snapshot and never counts against the caps.
 */
public final class StableHorseDeathHandler {

    private StableHorseDeathHandler() {
    }

    /** A pool horse died; dispatched by {@code HorseEvents}. */
    public static void onDeath(WorldServer world, EntityHorse horse) {
        UUID id = horse.getUniqueID();

        PlayerZoneManager mgr = PlayerZoneManager.get(world);
        for (PlayerZoneRecord zone : mgr.getAll()) {
            if (zone.type != ZoneType.STABLE || !(zone.data instanceof StableZoneData)) continue;
            StableZoneData data = (StableZoneData) zone.data;
            StableHorseEntry entry = data.findByLiveEntity(id);
            if (entry == null) continue;

            data.pool.remove(entry);
            mgr.markDirty();
            StableWatchLog.record(world, zone, StableWatchLog.Kind.LEAVE,
                    StableWatchLog.shortId(entry.poolId) + " died", true);
            return;
        }
    }
}
