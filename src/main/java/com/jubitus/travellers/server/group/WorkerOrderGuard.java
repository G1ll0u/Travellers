package com.jubitus.travellers.server.group;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.world.virtualization.VirtualTravellerManager;
import com.jubitus.travellers.world.virtualization.VirtualTravellerRecord;
import com.jubitus.travellers.world.zone.ZoneRef;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import java.util.UUID;

/**
 * Single authoritative "can this hired worker take a new player order?" predicate, shared by every
 * order entry point (individual Go, leader Go, Get-Equipment, and all group/leader reassignments).
 * <p>
 * A worker that is busy on a mission - transport/sale/donation ({@code TO_VILLAGE}/{@code RETURNING},
 * including waiting for pickup), recruiting, or fetching equipment ({@code GET_EQUIPMENT}) - must not
 * receive any of those orders, whether it is currently loaded or virtual. Both sides read from the
 * same source of truth: the loaded entity's {@link EntityTravellerWorker.WorkerMode} (via its
 * mission predicates) and the virtual record's {@code workerMode} string, so the answer never depends
 * on whether the player happens to be near the worker.
 * <p>
 * A pending "Go" order (loaded {@code goOrderActive} / record {@code IDLE} mode) is deliberately NOT
 * busy: re-targeting a Go, or grouping a worker that is merely walking to a point, is allowed.
 */
public final class WorkerOrderGuard {

    private WorkerOrderGuard() {
    }

    public static boolean isBusy(EntityTravellerWorker w) {
        return w.isJobActive() || w.isWaitingForPickup() || w.isOnRecruitMission();
    }

    public static boolean isBusy(VirtualTravellerRecord r) {
        return "WORKER".equals(r.entityClass) && !"IDLE".equals(r.workerMode);
    }

    /** Tells the player why an order was refused: "works at <zone>, unassign first" for a worker on
     * duty, the generic busy notice otherwise. */
    public static void notifyRefused(EntityPlayerMP player, EntityTravellerWorker w) {
        ZoneRef ref = w.getZoneRef();
        if (w.isOnDuty() && ref != null) notifyOnDuty(player, w.getName(), ref);
        else NotificationService.notify(player, NotificationDefs.ORDER_WORKER_BUSY);
    }

    public static void notifyRefused(EntityPlayerMP player, VirtualTravellerRecord r) {
        ZoneRef ref = r.entityNbt != null ? ZoneRef.read(r.entityNbt) : null;
        if ("ON_DUTY".equals(r.workerMode) && ref != null) notifyOnDuty(player, r.name, ref);
        else NotificationService.notify(player, NotificationDefs.ORDER_WORKER_BUSY);
    }

    /** Same, by id (loaded body first, then record). */
    public static void notifyRefused(EntityPlayerMP player, UUID workerId) {
        MinecraftServer server = player.getServer();
        if (server != null) {
            for (WorldServer ws : server.worlds) {
                Entity e = ws.getEntityFromUuid(workerId);
                if (e instanceof EntityTravellerWorker && !e.isDead) {
                    notifyRefused(player, (EntityTravellerWorker) e);
                    return;
                }
            }
            VirtualTravellerRecord r = VirtualTravellerManager.get(server.getWorld(0)).getRecord(workerId);
            if (r != null) {
                notifyRefused(player, r);
                return;
            }
        }
        NotificationService.notify(player, NotificationDefs.ORDER_WORKER_BUSY);
    }

    private static void notifyOnDuty(EntityPlayerMP player, String workerName, ZoneRef ref) {
        String zoneName = ref.name == null || ref.name.isEmpty() ? "?" : ref.name;
        NotificationService.notify(player, NotificationDefs.ORDER_WORKER_ON_DUTY,
                workerName == null || workerName.isEmpty() ? "Worker" : workerName, zoneName);
    }
}
