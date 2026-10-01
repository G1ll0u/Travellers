package com.jubitus.travellers.notify;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.world.zone.ZoneAssignments;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.util.UUID;

/** Hired-worker death and bed-respawn notices, the same for a loaded death, a loaded battle death and an offscreen battle death. */
public final class WorkerLifecycleNotifications {

    private WorkerLifecycleNotifications() {
    }

    /**
     * Death notice: vanilla's own death wording when known, plus whether (and roughly when) the worker
     * comes back at its bed. Call after {@link ZoneAssignments#onWorkerDied}, which decides the respawn.
     */
    public static void died(@Nullable MinecraftServer server, @Nullable UUID ownerId, UUID workerId, String name,
                            NoticePlace place, @Nullable VanillaDeathMessage.Resolved cause,
                            @Nullable BattleSummary summary) {
        if (server == null || ownerId == null) return;
        String key = cause != null ? cause.i18nKey : NotificationDefs.WORKER_DIED.i18nKey;
        String[] args = cause != null ? cause.args : new String[]{name};
        NotificationService.notifyEventOfflineRaw(server, ownerId, key, summary, place,
                respawnLine(server, workerId), args);
    }

    public static void respawned(@Nullable MinecraftServer server, @Nullable UUID ownerId, String name, NoticePlace place) {
        if (server == null || ownerId == null) return;
        NotificationService.notifyOfflineAt(server, ownerId, NotificationDefs.WORKER_RESPAWNED, place, null, name);
    }

    public static void respawnCancelled(@Nullable MinecraftServer server, @Nullable UUID ownerId, String name) {
        if (server == null || ownerId == null) return;
        NotificationService.notifyOffline(server, ownerId, NotificationDefs.WORKER_RESPAWN_CANCELLED, name);
    }

    @Nullable
    private static NoticeLine respawnLine(MinecraftServer server, UUID workerId) {
        if (!TravellersConfig.WORKER.workerRespawnAtBed) return null;
        int days = ZoneAssignments.pendingRespawnDays(server, workerId);
        if (days < 0) return NoticeLine.of(NotificationDefs.WORKER_DEATH_NO_BED);
        if (days <= 1) return NoticeLine.of(NotificationDefs.WORKER_DEATH_RESPAWN_TOMORROW);
        return NoticeLine.of(NotificationDefs.WORKER_DEATH_RESPAWN_DAYS, String.valueOf(days));
    }
}
