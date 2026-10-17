package com.jubitus.travellers.notify;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.zone.ZoneAssignments;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.util.UUID;

/** Hired-worker death and bed-respawn notices outside a battle; a battle death is told by {@link BattleNotifications}. */
public final class WorkerLifecycleNotifications {

    /** {@link #respawnOutlook}: bed respawn is switched off. */
    public static final int OUTLOOK_NO_RESPAWN = 0;
    /** {@link #respawnOutlook}: the worker had no bed and will not come back. */
    public static final int OUTLOOK_NO_BED = -1;

    private WorkerLifecycleNotifications() {
    }

    /**
     * Death notice: vanilla's own death wording when known, plus whether (and roughly when) the worker
     * comes back at its bed. Call after {@link ZoneAssignments#onWorkerDied}, which decides the respawn.
     */
    public static void died(@Nullable MinecraftServer server, @Nullable UUID ownerId, UUID workerId, String name,
                            NoticePlace place, @Nullable VanillaDeathMessage.Resolved cause) {
        if (server == null || ownerId == null) return;
        String key = cause != null ? cause.i18nKey : NotificationDefs.WORKER_DIED.i18nKey;
        String[] args = cause != null ? cause.args : new String[]{name};
        NotificationService.notifyDangerOfflineRaw(server, ownerId, key, place,
                respawnLine(respawnOutlook(server, workerId)), args);
    }

    public static void respawned(@Nullable MinecraftServer server, @Nullable UUID ownerId, String name, NoticePlace place) {
        if (server == null || ownerId == null) return;
        NotificationService.notifyOfflineAt(server, ownerId, NotificationDefs.WORKER_RESPAWNED, place, null, name);
    }

    public static void respawnCancelled(@Nullable MinecraftServer server, @Nullable UUID ownerId, String name) {
        if (server == null || ownerId == null) return;
        NotificationService.notifyOffline(server, ownerId, NotificationDefs.WORKER_RESPAWN_CANCELLED, name);
    }

    /** Days until the dead worker is back at its bed (at least 1), or {@link #OUTLOOK_NO_BED} / {@link #OUTLOOK_NO_RESPAWN}. */
    public static int respawnOutlook(MinecraftServer server, UUID workerId) {
        if (!TravellersConfig.WORKER.workerRespawnAtBed) return OUTLOOK_NO_RESPAWN;
        int days = ZoneAssignments.pendingRespawnDays(server, workerId);
        return days < 0 ? OUTLOOK_NO_BED : Math.max(1, days);
    }

    @Nullable
    private static NoticeLine respawnLine(int outlook) {
        if (outlook == OUTLOOK_NO_RESPAWN) return null;
        if (outlook == OUTLOOK_NO_BED) return NoticeLine.of(NotificationDefs.WORKER_DEATH_NO_BED);
        if (outlook <= 1) return NoticeLine.of(NotificationDefs.WORKER_DEATH_RESPAWN_TOMORROW);
        return NoticeLine.of(NotificationDefs.WORKER_DEATH_RESPAWN_DAYS, String.valueOf(outlook));
    }
}
