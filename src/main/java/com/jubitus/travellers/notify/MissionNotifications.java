package com.jubitus.travellers.notify;

import com.jubitus.travellers.server.group.WorkerGroupService;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Player-facing results of worker missions, shared by the loaded entity and the virtual simulation
 * so a mission finishing offscreen reads exactly like one finishing next to the player.
 */
public final class MissionNotifications {

    private MissionNotifications() {
    }

    /** Go-to arrival: solo message, or one group message when the worker leads followers. */
    public static void arrived(@Nullable MinecraftServer server, @Nullable UUID ownerId, UUID workerId) {
        if (server == null || ownerId == null) return;
        int followers = WorkerGroupService.countFollowers(server, ownerId, workerId);
        if (followers > 0) {
            NotificationService.notifyOffline(server, ownerId, NotificationDefs.GOTO_GROUP_ARRIVED,
                    String.valueOf(followers + 1));
        } else {
            NotificationService.notifyOffline(server, ownerId, NotificationDefs.GOTO_ARRIVED);
        }
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
}
