package com.jubitus.travellers.world.zone.stable;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.world.virtualization.VirtualTravellerManager;
import com.jubitus.travellers.world.virtualization.VirtualTravellerRecord;
import com.jubitus.travellers.world.zone.BlockRoleScanners;
import com.jubitus.travellers.world.zone.PlayerZoneManager;
import com.jubitus.travellers.world.zone.PlayerZoneRecord;
import com.jubitus.travellers.world.zone.ZoneRoles;
import com.jubitus.travellers.world.zone.duty.DutyDefs;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything about a Stable's palfrenier beds: how many palfreniers are on station (the number that
 * drives {@link StableWork}), bed-break detection + auto-recovery, and the "who is assigned
 * here" collection. Split out of {@code StableCoordinator} so that class stays a thin per-tick driver.
 */
public final class StableBedService {

    static final long BED_RESCAN_INTERVAL = 100L;

    private StableBedService() {
    }

    /**
     * How many <b>loaded</b> palfreniers assigned to {@code zone} are on station: on duty here,
     * arrived (not still travelling), holding a valid bed, not asleep and not fighting. When this is
     * &gt; 0 the loaded palfrenier AI ({@link StableHandTask}) is what advances the pool and the
     * offscreen timer pass must stay off.
     */
    static int countLoadedPalfreniersOnStation(WorldServer world, PlayerZoneRecord zone, StableZoneData data) {
        int n = 0;
        for (EntityTravellerWorker w : world.getEntitiesWithinAABB(EntityTravellerWorker.class, workerScanBox(zone))) {
            if (!w.isEntityAlive() || !w.isOnDuty() || w.isGoingToDuty()) continue;
            if (!DutyDefs.STABLE_HAND.equals(w.getDutyKind())) continue;
            if (!zone.center.equals(w.getDutyZoneCenter())) continue;
            if (!zone.ownerUUID.equals(w.getFollowOwnerId())) continue;
            if (!data.bedByWorker.containsKey(w.getUniqueID())) continue;
            if (w.isInCombat() || w.isSleepingInBed()) continue;
            n++;
        }
        return n;
    }

    /**
     * How many <b>virtual</b> records assigned to {@code zone} are on station (arrived, has a bed,
     * not in a battle). Only meaningful while the zone is unloaded - a loaded zone's palfrenier
     * would already be a materialized entity counted by {@link #countLoadedPalfreniersOnStation}.
     */
    static int countVirtualPalfreniersOnStation(WorldServer world, PlayerZoneRecord zone, StableZoneData data) {
        WorldServer overworld = world.getMinecraftServer() != null ? world.getMinecraftServer().getWorld(0) : null;
        if (overworld == null) return 0;
        int n = 0;
        double arriveSq = 48.0 * 48.0; // mirrors VirtualTravellerManager.JOB_ARRIVAL_FORCE_DIST
        for (VirtualTravellerRecord r : VirtualTravellerManager.get(overworld).getRecordsView()) {
            if (r.dutyKind == null || !zone.center.equals(r.dutyZoneCenter)) continue;
            if (!zone.ownerUUID.equals(r.followOwnerUUID)) continue;
            if (!data.bedByWorker.containsKey(r.uuid)) continue;
            if (r.battleId != null) continue;
            double dx = zone.center.getX() + 0.5 - r.posX;
            double dz = zone.center.getZ() + 0.5 - r.posZ;
            if (dx * dx + dz * dz > arriveSq) continue;
            n++;
        }
        return n;
    }

    /** The zone's box grown enough to contain a palfrenier working anywhere in the pen (plus slack
     * for one pathing around the edge). */
    static AxisAlignedBB workerScanBox(PlayerZoneRecord zone) {
        int gx = zone.halfWidth + 8, gz = zone.halfDepth + 8, gy = zone.halfHeight + 4;
        return new AxisAlignedBB(
                zone.center.getX() - gx, zone.center.getY() - gy, zone.center.getZ() - gz,
                zone.center.getX() + gx + 1, zone.center.getY() + gy + 1, zone.center.getZ() + gz + 1);
    }

    /** Every worker (live or virtual) assigned to Stable duty at {@code zone}, whatever its travel /
     * bed / combat state - used for bed auto-assignment, which must reach a palfrenier that has no
     * bed yet. */
    static Set<UUID> collectAssignedPalfreniers(WorldServer world, PlayerZoneRecord zone) {
        Set<UUID> ids = new HashSet<>();
        for (EntityTravellerWorker w : world.getEntitiesWithinAABB(EntityTravellerWorker.class, workerScanBox(zone))) {
            if (w.isEntityAlive()
                    && DutyDefs.STABLE_HAND.equals(w.getDutyKind())
                    && zone.center.equals(w.getDutyZoneCenter())
                    && zone.ownerUUID.equals(w.getFollowOwnerId())) {
                ids.add(w.getUniqueID());
            }
        }
        WorldServer overworld = world.getMinecraftServer() != null ? world.getMinecraftServer().getWorld(0) : null;
        if (overworld != null) {
            for (VirtualTravellerRecord r : VirtualTravellerManager.get(overworld).getRecordsView()) {
                if (r.dutyKind != null && zone.center.equals(r.dutyZoneCenter)
                        && zone.ownerUUID.equals(r.followOwnerUUID)) {
                    ids.add(r.uuid);
                }
            }
        }
        return ids;
    }

    /** Rescans a loaded Stable's beds: prunes assignments whose bed was removed (notifying the owner)
     * and auto-assigns a freed bed to any bedless palfrenier. Returns true if anything changed. */
    static boolean rescanBeds(WorldServer world, PlayerZoneRecord zone, StableZoneData data) {
        List<BlockPos> fresh = PlayerZoneManager.scanRole(world, zone.center, zone.halfWidth,
                zone.halfDepth, zone.halfHeight, BlockRoleScanners.BED);
        Set<BlockPos> valid = new HashSet<>(fresh);
        boolean changed = !valid.equals(new HashSet<>(zone.getRolePositions(ZoneRoles.BED)));
        zone.setRolePositions(ZoneRoles.BED, fresh);

        Iterator<Map.Entry<UUID, BlockPos>> it = data.bedByWorker.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, BlockPos> e = it.next();
            if (!valid.contains(e.getValue())) {
                it.remove();
                changed = true;
                notifyBedLost(world, zone, e.getKey());
            }
        }

        if (!fresh.isEmpty()) {
            for (UUID id : collectAssignedPalfreniers(world, zone)) {
                if (data.bedByWorker.containsKey(id)) continue;
                BlockPos free = zone.pickFreeBed();
                if (free == null) break;
                data.bedByWorker.put(id, free);
                changed = true;
            }
        }
        return changed;
    }

    /** Called from {@code PlayerZoneManager.onBedBroken} the instant a tracked Stable bed is broken.
     * A bedless palfrenier stays on duty (ambient behaviour only) until a bed is restored. */
    public static void onStableBedBroken(WorldServer world, PlayerZoneManager mgr, PlayerZoneRecord zone,
                                         StableZoneData data, BlockPos headPos) {
        UUID workerUUID = null;
        for (Map.Entry<UUID, BlockPos> e : data.bedByWorker.entrySet()) {
            if (headPos.equals(e.getValue())) { workerUUID = e.getKey(); break; }
        }
        if (workerUUID != null) {
            data.bedByWorker.remove(workerUUID);
            notifyBedLost(world, zone, workerUUID);
        }
        List<BlockPos> beds = new ArrayList<>(zone.getRolePositions(ZoneRoles.BED));
        beds.remove(headPos);
        zone.setRolePositions(ZoneRoles.BED, beds);
        mgr.markDirty();
    }

    private static void notifyBedLost(WorldServer world, PlayerZoneRecord zone, UUID workerUUID) {
        if (world.getMinecraftServer() == null) return;
        String stableName = (zone.name == null || zone.name.isEmpty()) ? "Stable" : zone.name;
        NotificationService.notifyOffline(world.getMinecraftServer(), zone.ownerUUID,
                NotificationDefs.STABLE_BED_DESTROYED, resolveWorkerName(world, workerUUID), stableName);
    }

    static String resolveWorkerName(WorldServer world, UUID workerUUID) {
        Entity e = world.getEntityFromUuid(workerUUID);
        if (e instanceof EntityTravellerWorker && e.getName() != null && !e.getName().isEmpty()) {
            return e.getName();
        }
        WorldServer overworld = world.getMinecraftServer() != null ? world.getMinecraftServer().getWorld(0) : null;
        if (overworld != null) {
            VirtualTravellerRecord r = VirtualTravellerManager.get(overworld).getRecord(workerUUID);
            if (r != null && r.name != null && !r.name.isEmpty()) return r.name;
        }
        return "Worker";
    }
}
