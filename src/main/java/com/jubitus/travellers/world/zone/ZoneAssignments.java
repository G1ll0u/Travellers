package com.jubitus.travellers.world.zone;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
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
import net.minecraftforge.common.DimensionManager;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** The one place that detaches a worker (loaded or virtual) from a player zone: duty, home, bed. */
public final class ZoneAssignments {

    private static final long AUDIT_INTERVAL_TICKS = 600L;

    private ZoneAssignments() {
    }

    /** True if the worker already has a duty or home in a zone other than {@code zoneCenter}: one zone per worker. */
    public static boolean isBoundElsewhere(EntityTravellerWorker w, BlockPos zoneCenter) {
        BlockPos duty = w.getDutyZoneCenter();
        BlockPos home = w.getAssignedHomePos();
        return (duty != null && !duty.equals(zoneCenter)) || (home != null && !home.equals(zoneCenter));
    }

    /** Detaches {@code workerUUID} from the zone at {@code zoneCenter}. Returns true if anything changed. */
    public static boolean release(MinecraftServer server, UUID workerUUID, BlockPos zoneCenter) {
        freeBeds(server, workerUUID, zoneCenter);

        EntityTravellerWorker live = findLoaded(server, workerUUID);
        if (live != null) {
            if (!live.releaseFromZone(zoneCenter)) return false;
            EntityPlayerMP owner = live.getFollowOwnerId() != null
                    ? server.getPlayerList().getPlayerByUUID(live.getFollowOwnerId()) : null;
            if (owner != null) TravellerFollowNet.CHANNEL.sendTo(new MsgSyncWorkerHome(live), owner);
            return true;
        }

        WorldServer overworld = server.getWorld(0);
        VirtualTravellerManager vwm = VirtualTravellerManager.get(overworld);
        VirtualTravellerRecord r = vwm.getRecord(workerUUID);
        if (r == null || r.entityNbt == null) return false;
        EntityTravellerWorker temp = new EntityTravellerWorker(overworld);
        temp.readFromNBT(r.entityNbt);
        temp.setUniqueId(r.uuid);
        boolean changed = temp.releaseFromZone(zoneCenter);
        if (zoneCenter.equals(r.dutyZoneCenter)) changed = true;
        if (!changed) return false;

        NBTTagCompound updated = new NBTTagCompound();
        temp.writeToNBT(updated);
        r.entityNbt = updated;
        r.dutyKind = temp.getDutyKind();
        r.dutyZoneCenter = temp.getDutyZoneCenter();
        r.goingToDuty = temp.isGoingToDuty();
        r.goingHome = temp.isGoingHome();
        r.personalTravelGoal = temp.getActivePersonalTravelGoal();
        r.followOrderActive = temp.isFollowOrderActive();
        r.workerMode = temp.getMode().name();
        r.goal = null;
        vwm.markDirty();
        WorldServer w = server.getWorld(r.dimension);
        vwm.forceRefreshGoal(w != null ? w : overworld, r, overworld.getTotalWorldTime());
        return true;
    }

    /** Detaches every worker bound to {@code zone}, then runs the type-specific teardown. Call before removing the record. */
    public static void onZoneDeleted(WorldServer world, PlayerZoneRecord zone) {
        MinecraftServer server = world.getMinecraftServer();
        if (server == null) return;
        for (UUID workerUUID : collectBoundWorkers(server, zone)) {
            release(server, workerUUID, zone.center);
        }
        PlayerZoneManager.get(world).dropPendingRespawnsForZone(zone.center);
        if (zone.data instanceof ZoneBedData) ((ZoneBedData) zone.data).bedByWorker().clear();

        if (zone.type == ZoneType.STABLE) {
            com.jubitus.travellers.world.zone.stable.StableCoordinator.get(world).releaseZone(world, zone);
        }
        PlayerZoneManager.get(world).markDirty();
    }

    /** Periodically releases workers whose duty or home points at a zone that no longer exists. */
    public static void tickAudit(WorldServer overworld, long now) {
        if (now % AUDIT_INTERVAL_TICKS != 0L) return;
        MinecraftServer server = overworld.getMinecraftServer();
        if (server == null) return;

        List<Object[]> stale = new ArrayList<>();
        for (WorldServer ws : server.worlds) {
            for (Entity e : new ArrayList<>(ws.loadedEntityList)) {
                if (!(e instanceof EntityTravellerWorker) || e.isDead) continue;
                EntityTravellerWorker w = (EntityTravellerWorker) e;
                UUID owner = w.getFollowOwnerId();
                if (owner == null) continue;
                addIfMissing(stale, w.getUniqueID(), owner, w.getDutyZoneCenter(), w.getDutyZoneDim());
                addIfMissing(stale, w.getUniqueID(), owner, w.getAssignedHomePos(), w.getAssignedHomeDim());
            }
        }
        for (VirtualTravellerRecord r : VirtualTravellerManager.get(overworld).getRecordsView()) {
            if (!r.isHiredFollower || r.followOwnerUUID == null) continue;
            if (r.dutyZoneCenter != null) addIfMissing(stale, r.uuid, r.followOwnerUUID, r.dutyZoneCenter, dutyDimOf(r));
            NBTTagCompound nbt = r.entityNbt;
            if (nbt != null && nbt.hasKey("HomeX")) {
                BlockPos home = new BlockPos(nbt.getInteger("HomeX"), nbt.getInteger("HomeY"), nbt.getInteger("HomeZ"));
                addIfMissing(stale, r.uuid, r.followOwnerUUID, home, nbt.getInteger("HomeDim"));
            }
        }
        for (Object[] s : stale) {
            release(server, (UUID) s[0], (BlockPos) s[1]);
        }
    }

    private static void addIfMissing(List<Object[]> out, UUID worker, UUID owner, @Nullable BlockPos center, int dim) {
        if (center == null) return;
        WorldServer zoneWorld = DimensionManager.getWorld(dim);
        if (zoneWorld == null) return; // dimension not loaded: can't tell, check later
        if (PlayerZoneManager.get(zoneWorld).getByOwnerAndCenter(owner, center) != null) return;
        out.add(new Object[]{worker, center});
    }

    private static int dutyDimOf(VirtualTravellerRecord r) {
        NBTTagCompound nbt = r.entityNbt;
        return nbt != null && nbt.hasKey("DutyDim") ? nbt.getInteger("DutyDim") : r.dimension;
    }

    private static Set<UUID> collectBoundWorkers(MinecraftServer server, PlayerZoneRecord zone) {
        Set<UUID> out = new LinkedHashSet<>();
        if (zone.data instanceof ZoneBedData) out.addAll(((ZoneBedData) zone.data).bedByWorker().keySet());
        for (WorldServer ws : server.worlds) {
            for (Entity e : ws.loadedEntityList) {
                if (!(e instanceof EntityTravellerWorker)) continue;
                EntityTravellerWorker w = (EntityTravellerWorker) e;
                if (!zone.ownerUUID.equals(w.getFollowOwnerId())) continue;
                if (zone.center.equals(w.getDutyZoneCenter()) || zone.center.equals(w.getAssignedHomePos())) {
                    out.add(w.getUniqueID());
                }
            }
        }
        for (VirtualTravellerRecord r : VirtualTravellerManager.get(server.getWorld(0)).getRecordsView()) {
            if (!zone.ownerUUID.equals(r.followOwnerUUID)) continue;
            NBTTagCompound nbt = r.entityNbt;
            boolean home = nbt != null && nbt.hasKey("HomeX")
                    && zone.center.getX() == nbt.getInteger("HomeX")
                    && zone.center.getY() == nbt.getInteger("HomeY")
                    && zone.center.getZ() == nbt.getInteger("HomeZ");
            if (home || zone.center.equals(r.dutyZoneCenter)) out.add(r.uuid);
        }
        return out;
    }

    private static void freeBeds(MinecraftServer server, UUID workerUUID, BlockPos zoneCenter) {
        for (WorldServer ws : server.worlds) {
            PlayerZoneManager mgr = PlayerZoneManager.get(ws);
            for (PlayerZoneRecord z : mgr.getAll()) {
                if (!zoneCenter.equals(z.center) || !(z.data instanceof ZoneBedData)) continue;
                if (((ZoneBedData) z.data).bedByWorker().remove(workerUUID) != null) mgr.markDirty();
            }
        }
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
