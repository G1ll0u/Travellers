package com.jubitus.travellers.notify;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.follow.group.WorkerGroupService;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Player-facing results of worker missions, shared by the loaded entity and the virtual simulation
 * so a mission finishing offscreen reads exactly like one finishing next to the player.
 */
public final class MissionNotifications {

    private MissionNotifications() {
    }

    private static final class Trip {
        final double distance;
        final long startTick;

        Trip(double distance, long startTick) {
            this.distance = distance;
            this.startTick = startTick;
        }
    }

    // Go orders in progress, by the worker that received them. Not persisted: a trip whose start was
    // lost (restart) is always announced, like before this filter existed.
    private static final Map<UUID, Trip> TRIPS = new HashMap<>();
    private static final int MAX_TRIPS = 512;

    /** A Go order was given. Short repositioning moves stay silent, here and on arrival. */
    public static void goOrdered(EntityPlayer player, UUID workerId, BlockPos from, BlockPos to,
                                 NotificationDef startDef, String... args) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        double distance = Math.sqrt(from.distanceSq(to.getX(), from.getY(), to.getZ()));
        if (TRIPS.size() >= MAX_TRIPS) TRIPS.clear();
        TRIPS.put(workerId, new Trip(distance, server.getWorld(0).getTotalWorldTime()));
        if (distance >= TravellersConfig.WORKER.goNotifyMinDistance) {
            NotificationService.notify(player, startDef, args);
        }
    }

    /** Go-to arrival: solo message, or one group message when the worker leads followers. */
    public static void arrived(@Nullable MinecraftServer server, @Nullable UUID ownerId, UUID workerId, NoticePlace place) {
        if (server == null || ownerId == null) return;
        Trip trip = TRIPS.remove(workerId);
        if (trip != null && !worthTelling(server, trip)) return;
        int followers = WorkerGroupService.countFollowers(server, ownerId, workerId);
        if (followers > 0) {
            NotificationService.notifyOfflineAt(server, ownerId, NotificationDefs.GOTO_GROUP_ARRIVED, place, null,
                    String.valueOf(followers + 1));
        } else {
            NotificationService.notifyOfflineAt(server, ownerId, NotificationDefs.GOTO_ARRIVED, place, null);
        }
    }

    private static boolean worthTelling(MinecraftServer server, Trip trip) {
        long seconds = (server.getWorld(0).getTotalWorldTime() - trip.startTick) / 20L;
        return trip.distance >= TravellersConfig.WORKER.goNotifyMinDistance
                || seconds >= TravellersConfig.WORKER.goNotifyMinSeconds;
    }

    /** A worker back from a sale finds its employer away: home to its Barrack, or waiting where they were last seen. */
    public static void cargoEmployerAbsent(@Nullable MinecraftServer server, @Nullable UUID ownerId,
                                           String workerName, boolean goingHome, NoticePlace place) {
        if (server == null || ownerId == null) return;
        NotificationService.notifyOfflineAt(server, ownerId, goingHome
                ? NotificationDefs.WORKER_CARGO_HEADING_HOME : NotificationDefs.WORKER_CARGO_WAITING, place, null, workerName);
    }

    /** The employer is back: the worker sets off toward them with the cargo. */
    public static void cargoComingBack(@Nullable MinecraftServer server, @Nullable UUID ownerId, String workerName,
                                       NoticePlace place) {
        if (server == null || ownerId == null) return;
        NotificationService.notifyOfflineAt(server, ownerId, NotificationDefs.WORKER_CARGO_COMING_BACK, place, null, workerName);
    }

    /** Get-equipment result: how many of the workers (the leader plus its followers) got something. */
    public static void equipped(@Nullable MinecraftServer server, @Nullable UUID ownerId, NoticePlace place,
                                int equipped, int total) {
        if (server == null || ownerId == null) return;
        if (equipped <= 0) {
            NotificationService.notifyOfflineAt(server, ownerId, NotificationDefs.EQUIP_NOTHING, place, null);
        } else {
            NotificationService.notifyOfflineAt(server, ownerId, NotificationDefs.EQUIP_DONE, place, null,
                    String.valueOf(equipped), String.valueOf(total));
        }
    }

    /** Dump-items result: items stored, items that did not fit, workers who dropped something. */
    public static void dumped(@Nullable MinecraftServer server, @Nullable UUID ownerId, NoticePlace place,
                              int stored, int left, int workers) {
        if (server == null || ownerId == null) return;
        if (left > 0) {
            NotificationService.notifyOfflineAt(server, ownerId, NotificationDefs.DUMP_FULL, place, null,
                    String.valueOf(stored), String.valueOf(left));
        } else if (stored > 0) {
            NotificationService.notifyOfflineAt(server, ownerId, NotificationDefs.DUMP_DONE, place, null,
                    String.valueOf(stored), String.valueOf(workers));
        } else {
            NotificationService.notifyOfflineAt(server, ownerId, NotificationDefs.DUMP_NOTHING, place, null);
        }
    }

    /** Take-horses result: how many of the workers (the leader plus its followers) took a mount. */
    public static void horsesTaken(@Nullable MinecraftServer server, @Nullable UUID ownerId, NoticePlace place,
                                   int taken, int total, String stableName) {
        if (server == null || ownerId == null) return;
        NotificationService.notifyOfflineAt(server, ownerId, NotificationDefs.STABLE_HORSES_TAKEN, place, null,
                String.valueOf(taken), String.valueOf(total), stableName);
    }

    /** Return-horses result: how many of the workers (the leader plus its followers) gave a horse back. */
    public static void horsesReturned(@Nullable MinecraftServer server, @Nullable UUID ownerId, NoticePlace place,
                                      int returned, int total, String stableName) {
        if (server == null || ownerId == null) return;
        if (returned <= 0) {
            NotificationService.notifyOfflineAt(server, ownerId, NotificationDefs.STABLE_NO_HORSE_TO_RETURN, place, null);
        } else {
            NotificationService.notifyOfflineAt(server, ownerId, NotificationDefs.STABLE_HORSES_RETURNED, place, null,
                    String.valueOf(returned), String.valueOf(total), stableName);
        }
    }
}
