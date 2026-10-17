package com.jubitus.travellers.zone.stable;

import com.jubitus.travellers.follow.group.WorkerGroupService;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.notify.MissionNotifications;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Return Horses at a Stable: the mirror of {@link WorkerHorsePickup}. The leader reaching the Stable
 * returns its own horse and every group follower's, loaded or virtual, exactly as Get Equipment serves
 * the whole group. A horse still alive inside a ticking Stable stays in the world as a live pool horse;
 * any other horse (dormant Stable, unloaded, outside the box) goes into the pool as data.
 */
public final class WorkerHorseReturn {

    private WorkerHorseReturn() {
    }

    public static boolean hasHorse(EntityTravellerWorker w) {
        return w.getMountUUID() != null || w.mountState.getPendingMountNbt() != null
                || w.mountState.getMountSnapshotNbt() != null;
    }

    public static boolean hasHorse(VirtualTravellerRecord r) {
        return r.mountNbt != null || (r.entityNbt != null && r.entityNbt.hasKey("MountSnapshot"));
    }

    /** Does the leader, or any hired follower it escorts, carry a horse? */
    public static boolean groupHasHorse(MinecraftServer server, UUID leaderId) {
        for (WorldServer ws : server.worlds) {
            for (EntityTravellerWorker w : ws.getEntities(EntityTravellerWorker.class, e -> e != null && !e.isDead
                    && (leaderId.equals(e.getUniqueID()) || leaderId.equals(e.getEscortLeaderUUID())))) {
                if (hasHorse(w)) return true;
            }
        }
        for (VirtualTravellerRecord r : VirtualTravellerManager.get(server.getWorld(0)).getRecordsView()) {
            if (!leaderId.equals(r.uuid) && !(r.isHiredFollower && leaderId.equals(r.escortLeaderUUID))) continue;
            if (hasHorse(r)) return true;
        }
        return false;
    }

    /** A loaded worker gives its horse back. False when it had none. */
    public static boolean returnLoaded(WorldServer world, PlayerZoneRecord zone, EntityTravellerWorker w) {
        if (!(zone.data instanceof StableZoneData) || !hasHorse(w)) return false;
        StableZoneData data = (StableZoneData) zone.data;

        UUID horseId = w.getMountUUID();
        Entity ride = horseId != null && w.world == world ? world.getEntityFromUuid(horseId) : null;
        StableCoordinator coord = StableCoordinator.get(world);
        if (ride instanceof EntityHorse && !ride.isDead && zone.contains(ride.getPosition())
                && coord.isZoneTicking(world, zone)) {
            EntityHorse horse = TravellerMountSupport.releaseLiveMount(w);
            if (horse != null) {
                coord.attachHorseToPool(world, zone, horse);
                return true;
            }
        }
        boolean given = StableMountRelease.handOverLoadedMount(world, w, data);
        if (given) {
            StableWatchLog.record(world, zone, StableWatchLog.Kind.INTAKE, "horse returned by a worker", true);
        }
        return given;
    }

    /** A virtual worker gives its horse back (pure data). False when it had none. */
    public static boolean returnVirtual(WorldServer world, PlayerZoneRecord zone, VirtualTravellerRecord r) {
        if (!(zone.data instanceof StableZoneData) || !hasHorse(r)) return false;
        StableMountRelease.surrenderVirtualMount(world, r, (StableZoneData) zone.data);
        r.invalidateNbtCaches();
        VirtualTravellerManager.get(world).markDirty();
        StableWatchLog.record(world, zone, StableWatchLog.Kind.INTAKE, "horse returned by a worker", true);
        return true;
    }

    /**
     * The leader reached the Stable and already handled its own horse ({@code leaderReturned}): serves
     * its group followers, then tells the owner. Pass {@code zone == null} when the Stable is gone.
     */
    public static void finishAtStable(WorldServer world, @Nullable PlayerZoneRecord zone, @Nullable UUID ownerId,
                                      UUID leaderId, boolean leaderReturned) {
        MinecraftServer srv = world.getMinecraftServer();
        if (srv == null) return;
        int returned = leaderReturned ? 1 : 0;
        if (zone != null) {
            for (WorldServer ws : srv.worlds) {
                for (EntityTravellerWorker w : ws.getEntities(EntityTravellerWorker.class, e -> e != null && !e.isDead
                        && e.isHiredFollower() && leaderId.equals(e.getEscortLeaderUUID()))) {
                    if (returnLoaded(world, zone, w)) returned++;
                }
            }
            for (VirtualTravellerRecord r : VirtualTravellerManager.get(world).getRecordsView()) {
                if (!r.isHiredFollower || !leaderId.equals(r.escortLeaderUUID)) continue;
                if (returnVirtual(world, zone, r)) returned++;
            }
        }
        int total = 1 + (ownerId != null ? WorkerGroupService.countFollowers(srv, ownerId, leaderId) : 0);
        String name = zone == null ? "Stable" : WorkerHorsePickup.stableName(zone);
        com.jubitus.travellers.notify.NoticePlace place = zone == null ? null
                : new com.jubitus.travellers.notify.NoticePlace(zone.center, world.provider.getDimension(), leaderId);
        MissionNotifications.horsesReturned(srv, ownerId, place, returned, total, name);
    }
}
