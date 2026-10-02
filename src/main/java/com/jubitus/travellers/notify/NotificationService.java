package com.jubitus.travellers.notify;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.notify.net.MsgJournalEntryAdded;
import com.jubitus.travellers.notify.net.MsgShowNotification;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Central dispatch point for on-screen notifications: sends the toast to a live player and,
 * for Event-type notifications, appends to that player's persistent journal. Offline owners
 * (only a UUID known, e.g. from offscreen/virtual systems) get their notice queued for delivery
 * on next login instead.
 */
public final class NotificationService {

    private NotificationService() {
    }

    public static void notify(EntityPlayer player, NotificationDef def, String... args) {
        if (player instanceof EntityPlayerMP) {
            dispatch(((EntityPlayerMP) player).getServer(), player.getUniqueID(), def.type, def.i18nKey, args, null, null, null);
        }
    }

    public static void notifyEvent(EntityPlayer player, NotificationDef def, BattleSummary summary, String... args) {
        if (player instanceof EntityPlayerMP) {
            dispatch(((EntityPlayerMP) player).getServer(), player.getUniqueID(), def.type, def.i18nKey, args, summary, null, null);
        }
    }

    public static void notifyOffline(MinecraftServer server, UUID ownerId, NotificationDef def, String... args) {
        dispatch(server, ownerId, def.type, def.i18nKey, args, null, null, null);
    }

    /** Same as {@link #notifyOffline}, located (the toast says where, the journal row centers the map there), with an optional second line. */
    public static void notifyOfflineAt(MinecraftServer server, UUID ownerId, NotificationDef def,
                                       @Nullable NoticePlace place, @Nullable NoticeLine detail, String... args) {
        dispatch(server, ownerId, def.type, def.i18nKey, args, null, place, detail);
    }

    public static void notifyEventOffline(MinecraftServer server, UUID ownerId, NotificationDef def,
                                          BattleSummary summary, String... args) {
        dispatch(server, ownerId, def.type, def.i18nKey, args, summary, null, null);
    }

    /**
     * For a dynamically-resolved i18n key that isn't (and can't be) a fixed {@link NotificationDef}
     * constant - e.g. a vanilla death-message key forwarded verbatim via {@link VanillaDeathMessage}.
     */
    public static void notifyEventOfflineRaw(MinecraftServer server, UUID ownerId, String i18nKey,
                                             @Nullable BattleSummary summary, @Nullable NoticePlace place,
                                             @Nullable NoticeLine detail, String... args) {
        dispatch(server, ownerId, NotificationType.EVENT, i18nKey, args, summary, place, detail);
    }

    private static void dispatch(@Nullable MinecraftServer server, UUID ownerId, NotificationType type,
                                 String i18nKey, String[] args, @Nullable BattleSummary summary,
                                 @Nullable NoticePlace place, @Nullable NoticeLine detail) {
        if (server == null || ownerId == null) return;
        if (!TravellersConfig.NOTIFICATIONS.masterEnabled) return;

        if (place == null && summary != null) {
            place = new NoticePlace(summary.location, summary.dimension, null);
        }
        long worldTime = server.getWorld(0).getTotalWorldTime();
        EntityPlayerMP online = server.getPlayerList().getPlayerByUUID(ownerId);

        // Journal write is unconditional for EVENT type (only gated by masterEnabled above) so the
        // history stays complete even when the on-screen toast toggle (showEvent) is off.
        if (type == NotificationType.EVENT) {
            JournalEntry entry = new JournalEntry(type, i18nKey, args, worldTime, summary, place, detail);
            NotificationJournalData.get(server.getWorld(0)).append(ownerId, entry);
            if (online != null) TravellersNetwork.sendToPlayer(online, new MsgJournalEntryAdded(entry));
        }

        if (!isTypeEnabled(type)) return;

        if (online != null) {
            TravellersNetwork.sendToPlayer(online, new MsgShowNotification(type, i18nKey, args, worldTime, place, detail));
        } else {
            NotificationQueueData.get(server.getWorld(0)).enqueue(ownerId, type, i18nKey, args, worldTime, place, detail);
        }
    }

    private static boolean isTypeEnabled(NotificationType type) {
        switch (type) {
            case INFO:
                return TravellersConfig.NOTIFICATIONS.showInfo;
            case WARNING:
                return TravellersConfig.NOTIFICATIONS.showWarning;
            case EVENT:
                return TravellersConfig.NOTIFICATIONS.showEvent;
            case ERROR:
                return TravellersConfig.NOTIFICATIONS.showError;
            default:
                return true;
        }
    }
}
