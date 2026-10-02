package com.jubitus.travellers.zone.stable;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import com.jubitus.travellers.zone.BlockRoleScanners;
import com.jubitus.travellers.zone.PlayerZoneManager;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import com.jubitus.travellers.zone.ZoneRoles;
import com.jubitus.travellers.zone.duty.DutyDefs;
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
            if (w.getZoneRef() == null || !w.getZoneRef().matches(zone)) continue;
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
            com.jubitus.travellers.zone.ZoneRef ref = r.entityNbt != null
                    ? com.jubitus.travellers.zone.ZoneRef.read(r.entityNbt) : null;
            if (ref == null || !ref.matches(zone)) continue;
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
}
