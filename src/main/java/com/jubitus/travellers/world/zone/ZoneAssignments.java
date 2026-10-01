package com.jubitus.travellers.world.zone;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.group.WorkerGroupService;
import com.jubitus.travellers.server.group.WorkerOrderGuard;
import com.jubitus.travellers.server.net.handler.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.home.MsgSyncWorkerHome;
import com.jubitus.travellers.world.virtualization.VirtualTravellerManager;
import com.jubitus.travellers.world.virtualization.VirtualTravellerRecord;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The one place that binds a worker (loaded or virtual) to a player zone and the one place that
 * detaches it. A worker has at most one zone ({@link ZoneRef}): a Barrack is its lodging, a Stable its
 * lodging and its duty. Binding always takes a bed.
 */
public final class ZoneAssignments {

    private static final long AUDIT_INTERVAL_TICKS = 600L;

    public enum AssignResult { OK, BOUND_ELSEWHERE, NO_BED, BUSY, NOT_FOUND }

    private ZoneAssignments() {
    }

    /** Binds {@code workerUUID} (owned by {@code player}) to {@code zone}, in the player's dimension. */
    public static AssignResult assign(EntityPlayerMP player, UUID workerUUID, PlayerZoneRecord zone) {
        MinecraftServer server = player.getServer();
        if (server == null) return AssignResult.NOT_FOUND;
        UUID owner = player.getUniqueID();
        int dim = player.getServerWorld().provider.getDimension();
        boolean duty = ZoneTypeDefs.get(zone.type).dutyKind != null;

        EntityTravellerWorker live = findLoaded(server, workerUUID);
        VirtualTravellerManager vwm = VirtualTravellerManager.get(server.getWorld(0));
        VirtualTravellerRecord rec = live == null ? vwm.getRecord(workerUUID) : null;
        if (live == null && (rec == null || rec.entityNbt == null)) return AssignResult.NOT_FOUND;
        if (!owner.equals(live != null ? live.getFollowOwnerId() : rec.followOwnerUUID)) return AssignResult.NOT_FOUND;

        ZoneRef current = live != null ? live.getZoneRef() : ZoneRef.read(rec.entityNbt);
        if (current != null) return current.matches(zone) ? AssignResult.OK : AssignResult.BOUND_ELSEWHERE;
        if (live != null ? WorkerOrderGuard.isBusy(live) : WorkerOrderGuard.isBusy(rec)) return AssignResult.BUSY;
        BlockPos bed = zone.pickFreeBed();
        if (zone.data instanceof ZoneBedData && bed == null) return AssignResult.NO_BED;

        // A worker put to work leaves its group: the follow AI and duty travel never share a worker.
        if (duty) {
            boolean leader = live != null ? live.isVisualLeader()
                    : WorkerGroupService.countFollowers(server, owner, workerUUID) > 0;
            UUID escortLeader = live != null ? live.getEscortLeaderUUID() : rec.escortLeaderUUID;
            if (leader) WorkerGroupService.comeBackGroup(player, workerUUID);
            else if (escortLeader != null) WorkerGroupService.comeBackMembers(player, Collections.singletonList(workerUUID));
        }

        if (live != null) {
            live.assignZone(zone, dim);
            if (duty) live.startDutyTravel();
            TravellerFollowNet.CHANNEL.sendTo(new MsgSyncWorkerHome(live), player);
        } else {
            WorldServer overworld = server.getWorld(0);
            EntityTravellerWorker temp = new EntityTravellerWorker(overworld);
            temp.readFromNBT(rec.entityNbt);
            temp.setUniqueId(rec.uuid);
            temp.setPositionAndRotation(rec.posX, rec.posY, rec.posZ, rec.rotYaw, 0);
            temp.assignZone(zone, dim);
            if (duty) temp.startDutyTravel();
            NBTTagCompound updated = new NBTTagCompound();
            temp.writeToNBT(updated);
            rec.entityNbt = updated;
            mirrorOntoRecord(temp, rec);
            if (duty) rec.goal = rec.personalTravelGoal;
            vwm.markDirty();
        }
        if (bed != null) ((ZoneBedData) zone.data).bedByWorker().put(workerUUID, bed);
        markZonesDirty(server);
        return AssignResult.OK;
    }

    /**
     * Detaches {@code workerUUID} from {@code zone} (or from whatever zone it has, when null - its
     * zone no longer exists). Returns true if anything changed.
     */
    public static boolean release(MinecraftServer server, UUID workerUUID, @Nullable PlayerZoneRecord zone) {
        boolean changed = freeBeds(server, workerUUID);

        EntityTravellerWorker live = findLoaded(server, workerUUID);
        if (live != null) {
            ZoneRef ref = live.getZoneRef();
            if (ref == null || (zone != null && !ref.matches(zone))) return changed;
            live.releaseFromZone();
            EntityPlayerMP owner = live.getFollowOwnerId() != null
                    ? server.getPlayerList().getPlayerByUUID(live.getFollowOwnerId()) : null;
            if (owner != null) TravellerFollowNet.CHANNEL.sendTo(new MsgSyncWorkerHome(live), owner);
            return true;
        }

        WorldServer overworld = server.getWorld(0);
        VirtualTravellerManager vwm = VirtualTravellerManager.get(overworld);
        VirtualTravellerRecord r = vwm.getRecord(workerUUID);
        if (r == null || r.entityNbt == null) return changed;
        ZoneRef ref = ZoneRef.read(r.entityNbt);
        if (ref == null || (zone != null && !ref.matches(zone))) return changed;
        EntityTravellerWorker temp = new EntityTravellerWorker(overworld);
        temp.readFromNBT(r.entityNbt);
        temp.setUniqueId(r.uuid);
        temp.releaseFromZone();

        NBTTagCompound updated = new NBTTagCompound();
        temp.writeToNBT(updated);
        ZoneRef.clear(updated);
        r.entityNbt = updated;
        mirrorOntoRecord(temp, r);
        r.goal = null;
        vwm.markDirty();
        WorldServer w = server.getWorld(r.dimension);
        vwm.forceRefreshGoal(w != null ? w : overworld, r, overworld.getTotalWorldTime());
        return true;
    }

    /** The record's own copies of the worker's zone and duty travel state. */
    private static void mirrorOntoRecord(EntityTravellerWorker w, VirtualTravellerRecord r) {
        r.dutyKind = w.getDutyKind();
        r.dutyZoneCenter = w.getDutyZoneCenter();
        r.goingToDuty = w.isGoingToDuty();
        r.goingHome = w.isGoingHome();
        r.personalTravelGoal = w.getActivePersonalTravelGoal();
        r.followOrderActive = w.isFollowOrderActive();
        r.workerMode = w.getMode().name();
    }

    /** A hired worker died, loaded or offscreen: it comes back beside its bed later (see
     * {@code PlayerZoneManager.processRespawns}), or its bed is freed at once. */
    public static void onWorkerDied(MinecraftServer server, UUID workerUUID, @Nullable UUID owner,
                                    @Nullable ZoneRef ref, NBTTagCompound strippedNbt) {
        if (ref == null) return;
        WorldServer zoneWorld = net.minecraftforge.common.DimensionManager.getWorld(ref.dim);
        if (zoneWorld == null) return;
        PlayerZoneManager mgr = PlayerZoneManager.get(zoneWorld);
        if (owner != null && com.jubitus.travellers.config.TravellersConfig.CONTROLLED_VILLAGE.workerRespawnInBarracks) {
            mgr.scheduleRespawn(owner, workerUUID, ref, strippedNbt);
        } else {
            mgr.unassignWorker(workerUUID);
        }
    }

    /** Detaches every worker bound to {@code zone}, then runs the type-specific teardown. Call before removing the record. */
    public static void onZoneDeleted(WorldServer world, PlayerZoneRecord zone) {
        MinecraftServer server = world.getMinecraftServer();
        if (server == null) return;
        for (UUID workerUUID : collectBoundWorkers(server, zone)) {
            release(server, workerUUID, zone);
        }
        PlayerZoneManager.get(world).dropPendingRespawnsForZone(zone);
        if (zone.data instanceof ZoneBedData) ((ZoneBedData) zone.data).bedByWorker().clear();

        if (zone.type == ZoneType.STABLE) {
            com.jubitus.travellers.world.zone.stable.StableCoordinator.get(world).releaseZone(world, zone);
        }
        PlayerZoneManager.get(world).markDirty();
    }

    /** Periodically releases workers whose zone no longer exists. */
    public static void tickAudit(WorldServer overworld, long now) {
        if (now % AUDIT_INTERVAL_TICKS != 0L) return;
        MinecraftServer server = overworld.getMinecraftServer();
        if (server == null) return;

        List<UUID> stale = new ArrayList<>();
        for (WorldServer ws : server.worlds) {
            for (Entity e : new ArrayList<>(ws.loadedEntityList)) {
                if (!(e instanceof EntityTravellerWorker) || e.isDead) continue;
                EntityTravellerWorker w = (EntityTravellerWorker) e;
                if (isGone(w.getZoneRef(), w.getFollowOwnerId())) stale.add(w.getUniqueID());
            }
        }
        for (VirtualTravellerRecord r : VirtualTravellerManager.get(overworld).getRecordsView()) {
            if (!r.isHiredFollower || r.entityNbt == null) continue;
            if (isGone(ZoneRef.read(r.entityNbt), r.followOwnerUUID)) stale.add(r.uuid);
        }
        for (UUID id : stale) release(server, id, null);
    }

    private static boolean isGone(@Nullable ZoneRef ref, @Nullable UUID owner) {
        if (ref == null || owner == null) return false;
        if (!ref.isDimensionLoaded()) return false; // can't tell yet, check later
        return ref.resolve(owner) == null;
    }

    /** The zone reference a worker currently holds, from its loaded body, its record or a pending
     * respawn - or null when none of those knows it (possibly just not loaded yet). */
    @Nullable
    static ZoneRef findRef(MinecraftServer server, UUID workerUUID) {
        EntityTravellerWorker live = findLoaded(server, workerUUID);
        if (live != null) return live.getZoneRef();
        VirtualTravellerRecord r = VirtualTravellerManager.get(server.getWorld(0)).getRecord(workerUUID);
        if (r != null && r.entityNbt != null) return ZoneRef.read(r.entityNbt);
        for (WorldServer ws : server.worlds) {
            PlayerZoneManager.PendingRespawn pr = PlayerZoneManager.get(ws).getPendingRespawnsView().get(workerUUID);
            if (pr != null && pr.workerNbt != null) return ZoneRef.read(pr.workerNbt);
        }
        return null;
    }

    static Set<UUID> collectBoundWorkers(MinecraftServer server, PlayerZoneRecord zone) {
        Set<UUID> out = new LinkedHashSet<>();
        if (zone.data instanceof ZoneBedData) out.addAll(((ZoneBedData) zone.data).bedByWorker().keySet());
        for (WorldServer ws : server.worlds) {
            for (Entity e : ws.loadedEntityList) {
                if (!(e instanceof EntityTravellerWorker)) continue;
                EntityTravellerWorker w = (EntityTravellerWorker) e;
                if (!zone.ownerUUID.equals(w.getFollowOwnerId())) continue;
                if (w.getZoneRef() != null && w.getZoneRef().matches(zone)) out.add(w.getUniqueID());
            }
        }
        for (VirtualTravellerRecord r : VirtualTravellerManager.get(server.getWorld(0)).getRecordsView()) {
            if (!zone.ownerUUID.equals(r.followOwnerUUID) || r.entityNbt == null) continue;
            ZoneRef ref = ZoneRef.read(r.entityNbt);
            if (ref != null && ref.matches(zone)) out.add(r.uuid);
        }
        return out;
    }

    private static boolean freeBeds(MinecraftServer server, UUID workerUUID) {
        boolean changed = false;
        for (WorldServer ws : server.worlds) {
            PlayerZoneManager mgr = PlayerZoneManager.get(ws);
            for (PlayerZoneRecord z : mgr.getAll()) {
                if (!(z.data instanceof ZoneBedData)) continue;
                if (((ZoneBedData) z.data).bedByWorker().remove(workerUUID) != null) {
                    mgr.markDirty();
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static void markZonesDirty(MinecraftServer server) {
        for (WorldServer ws : server.worlds) PlayerZoneManager.get(ws).markDirty();
    }

    @Nullable
    private static EntityTravellerWorker findLoaded(MinecraftServer server, UUID uuid) {
        for (WorldServer ws : server.worlds) {
            Entity e = ws.getEntityFromUuid(uuid);
            if (e instanceof EntityTravellerWorker && !e.isDead) return (EntityTravellerWorker) e;
        }
        return null;
    }
}
