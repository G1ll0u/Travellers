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
    public static void arrived(@Nullable MinecraftServer server, @Nullable UUID ownerId, UUID workerId) {
        if (server == null || ownerId == null) return;
        Trip trip = TRIPS.remove(workerId);
        if (trip != null && !worthTelling(server, trip)) return;
        int followers = WorkerGroupService.countFollowers(server, ownerId, workerId);
        if (followers > 0) {
            NotificationService.notifyOffline(server, ownerId, NotificationDefs.GOTO_GROUP_ARRIVED,
                    String.valueOf(followers + 1));
        } else {
            NotificationService.notifyOffline(server, ownerId, NotificationDefs.GOTO_ARRIVED);
        }
    }

    private static boolean worthTelling(MinecraftServer server, Trip trip) {
        long seconds = (server.getWorld(0).getTotalWorldTime() - trip.startTick) / 20L;
        return trip.distance >= TravellersConfig.WORKER.goNotifyMinDistance
                || seconds >= TravellersConfig.WORKER.goNotifyMinSeconds;
    }

    /** A worker back from a sale finds its employer away: home to its Barrack, or waiting where they were last seen. */
    public static void cargoEmployerAbsent(@Nullable MinecraftServer server, @Nullable UUID ownerId,
                                           String workerName, boolean goingHome) {
        if (server == null || ownerId == null) return;
        NotificationService.notifyOffline(server, ownerId, goingHome
                ? NotificationDefs.WORKER_CARGO_HEADING_HOME : NotificationDefs.WORKER_CARGO_WAITING, workerName);
    }

    /** The employer is back: the worker sets off toward them with the cargo. */
    public static void cargoComingBack(@Nullable MinecraftServer server, @Nullable UUID ownerId, String workerName) {
        if (server == null || ownerId == null) return;
        NotificationService.notifyOffline(server, ownerId, NotificationDefs.WORKER_CARGO_COMING_BACK, workerName);
    }

    /** Get-equipment result: how many of the workers (the leader plus its followers) got something. */
    public static void equipped(@Nullable MinecraftServer server, @Nullable UUID ownerId, int equipped, int total) {
        if (server == null || ownerId == null) return;
        if (equipped <= 0) {
            NotificationService.notifyOffline(server, ownerId, NotificationDefs.EQUIP_NOTHING);
        } else {
            NotificationService.notifyOffline(server, ownerId, NotificationDefs.EQUIP_DONE,
                    String.valueOf(equipped), String.valueOf(total));
        }
    }

    /** Take-horses result: how many of the workers (the leader plus its followers) took a mount. */
    public static void horsesTaken(@Nullable MinecraftServer server, @Nullable UUID ownerId, int taken,
                                   int total, String stableName) {
        if (server == null || ownerId == null) return;
        NotificationService.notifyOffline(server, ownerId, NotificationDefs.STABLE_HORSES_TAKEN,
                String.valueOf(taken), String.valueOf(total), stableName);
    }

    /** Return-horses result: how many of the workers (the leader plus its followers) gave a horse back. */
    public static void horsesReturned(@Nullable MinecraftServer server, @Nullable UUID ownerId, int returned,
                                      int total, String stableName) {
        if (server == null || ownerId == null) return;
        if (returned <= 0) {
            NotificationService.notifyOffline(server, ownerId, NotificationDefs.STABLE_NO_HORSE_TO_RETURN);
        } else {
            NotificationService.notifyOffline(server, ownerId, NotificationDefs.STABLE_HORSES_RETURNED,
                    String.valueOf(returned), String.valueOf(total), stableName);
        }
    }
}
