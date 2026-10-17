package com.jubitus.travellers.zone;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps every zone's role blocks (beds, chests, hitches) in sync with the world, identically for every
 * zone type, and applies the one bed rule: a worker whose bed is gone takes a free bed, otherwise it
 * is released from the zone. Runs only on a loaded box, periodically and right after a block change
 * inside one.
 */
public final class ZoneRoleRescan {

    /** Zone ids to rescan on the next tick, per dimension (a break event fires before the block goes). */
    private static final Map<Integer, Set<UUID>> PENDING = new ConcurrentHashMap<>();
    private static final Set<UUID> AUDITED = ConcurrentHashMap.newKeySet();

    /** Server stopping: the next world starts clean. */
    public static void resetAll() {
        PENDING.clear();
        AUDITED.clear();
    }

    private ZoneRoleRescan() {
    }

    /** A block changed at {@code pos}: the zone containing it is rescanned next tick. */
    public static void noteBlockChange(WorldServer world, BlockPos pos) {
        for (PlayerZoneRecord zone : PlayerZoneManager.get(world).getAll()) {
            if (zone.contains(pos)) {
                PENDING.computeIfAbsent(world.provider.getDimension(), k -> ConcurrentHashMap.newKeySet()).add(zone.id);
            }
        }
    }

    public static void tick(WorldServer world, long now) {
        Set<UUID> pending = PENDING.remove(world.provider.getDimension());
        long interval = TravellersConfig.WORKER.zoneRescanIntervalTicks;
        PlayerZoneManager mgr = PlayerZoneManager.get(world);
        for (PlayerZoneRecord zone : new ArrayList<>(mgr.getAll())) {
            // Periodic scans are spread over the interval, one zone's worth of blocks at a time.
            boolean due = (now + (zone.id.hashCode() & 0x7FFFFFFF)) % interval == 0L;
            if (due || (pending != null && pending.contains(zone.id))) rescan(world, mgr, zone);
        }
    }

    /** Rescans one zone now. No-op unless its whole box is loaded (never loads a chunk). */
    public static void rescan(WorldServer world, PlayerZoneManager mgr, PlayerZoneRecord zone) {
        if (!isBoxLoaded(world, zone)) return;

        boolean changed = false;
        for (BlockRoleRequirement req : ZoneTypeDefs.get(zone.type).requiredRoles) {
            List<BlockPos> fresh = PlayerZoneManager.scanRole(world, zone.center,
                    zone.halfWidth, zone.halfDepth, zone.halfHeight, req.scanner);
            if (!new HashSet<>(fresh).equals(new HashSet<>(zone.getRolePositions(req.role)))) {
                zone.setRolePositions(req.role, fresh);
                changed = true;
            }
        }
        if (zone.data instanceof ZoneBedData) changed |= applyBedRule(world, zone, (ZoneBedData) zone.data);
        if (changed) mgr.markDirty();
    }

    private static boolean applyBedRule(WorldServer world, PlayerZoneRecord zone, ZoneBedData data) {
        MinecraftServer server = world.getMinecraftServer();
        Set<BlockPos> beds = new HashSet<>(zone.getRolePositions(ZoneRoles.BED));
        List<UUID> lost = new ArrayList<>();
        for (Map.Entry<UUID, BlockPos> e : data.bedByWorker().entrySet()) {
            if (!beds.contains(e.getValue())) lost.add(e.getKey());
        }
        // Once per session per zone, for older saves: a bed held by a worker now bound to another
        // zone is freed, and a worker bound here with no bed is handled like a lost bed.
        boolean freed = false;
        if (server != null && AUDITED.add(zone.id)) {
            for (UUID id : new ArrayList<>(data.bedByWorker().keySet())) {
                ZoneRef ref = ZoneAssignments.findRef(server, id);
                if (ref != null && !ref.matches(zone)) {
                    data.bedByWorker().remove(id);
                    freed = true;
                }
            }
            for (UUID id : ZoneAssignments.collectBoundWorkers(server, zone)) {
                if (!data.bedByWorker().containsKey(id)) lost.add(id);
            }
        }
        if (lost.isEmpty()) return freed;

        for (UUID workerUUID : lost) {
            data.bedByWorker().remove(workerUUID);
            BlockPos free = zone.pickFreeBed();
            if (free != null) {
                data.bedByWorker().put(workerUUID, free);
                continue;
            }
            if (server == null) continue;
            String zoneName = zone.name == null || zone.name.isEmpty() ? "?" : zone.name;
            NotificationService.notifyOfflineAt(server, zone.ownerUUID, NotificationDefs.ZONE_BED_LOST,
                    new com.jubitus.travellers.notify.NoticePlace(zone.center, world.provider.getDimension(), workerUUID),
                    null, resolveWorkerName(world, workerUUID), zoneName);
            ZoneAssignments.release(server, workerUUID, zone);
        }
        return true;
    }

    private static boolean isBoxLoaded(WorldServer world, PlayerZoneRecord zone) {
        return world.isAreaLoaded(
                zone.center.add(-zone.halfWidth, -zone.halfHeight, -zone.halfDepth),
                zone.center.add(zone.halfWidth, zone.halfHeight, zone.halfDepth));
    }

    private static String resolveWorkerName(WorldServer world, UUID workerUUID) {
        Entity e = world.getEntityFromUuid(workerUUID);
        if (e instanceof EntityTravellerWorker && !e.getName().isEmpty()) return e.getName();
        MinecraftServer server = world.getMinecraftServer();
        if (server != null) {
            VirtualTravellerRecord r = VirtualTravellerManager.get(server.getWorld(0)).getRecord(workerUUID);
            if (r != null && r.name != null && !r.name.isEmpty()) return r.name;
        }
        return "Worker";
    }
}
