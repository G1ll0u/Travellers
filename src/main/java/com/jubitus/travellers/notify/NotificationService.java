package com.jubitus.travellers.notify;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.notify.MsgJournalEntryAdded;
import com.jubitus.travellers.server.net.message.notify.MsgShowNotification;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;

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
        notifyInternal(player, def.type, def.i18nKey, args, null);
    }

    public static void notifyEvent(EntityPlayer player, NotificationDef def, BattleSummary summary, String... args) {
        notifyInternal(player, def.type, def.i18nKey, args, summary);
    }

    public static void notifyOffline(MinecraftServer server, UUID ownerId, NotificationDef def, String... args) {
        notifyOfflineInternal(server, ownerId, def.type, def.i18nKey, args, null);
    }

    public static void notifyEventOffline(MinecraftServer server, UUID ownerId, NotificationDef def,
                                           BattleSummary summary, String... args) {
        notifyOfflineInternal(server, ownerId, def.type, def.i18nKey, args, summary);
    }

    /**
     * Same as {@link #notifyEventOffline} but for a dynamically-resolved i18n key that isn't (and
     * can't be) a fixed {@link NotificationDef} constant - e.g. a vanilla death-message key forwarded
     * verbatim via {@link VanillaDeathMessage}. No compile-time arg-count check for these.
     */
    public static void notifyEventOfflineRaw(MinecraftServer server, UUID ownerId, String i18nKey,
                                              BattleSummary summary, String... args) {
        notifyOfflineInternal(server, ownerId, NotificationType.EVENT, i18nKey, args, summary);
    }

    private static void notifyInternal(EntityPlayer player, NotificationType type, String i18nKey,
                                        String[] args, @Nullable BattleSummary summary) {
        if (!(player instanceof EntityPlayerMP)) {
            return;
        }
        if (!TravellersConfig.NOTIFICATIONS.masterEnabled) {
            return;
        }

        EntityPlayerMP mp = (EntityPlayerMP) player;
        long worldTime = mp.world.getTotalWorldTime();

        // Journal write is unconditional for EVENT type (only gated by masterEnabled above) so the
        // history stays complete even when the on-screen toast toggle (showEvent) is off.
        if (type == NotificationType.EVENT) {
            JournalEntry entry = new JournalEntry(type, i18nKey, args, worldTime, summary);
            NotificationJournalData.get(mp.world).append(mp.getUniqueID(), entry);
            TravellerFollowNet.sendToPlayer(mp, new MsgJournalEntryAdded(entry));
        }

        if (!isTypeEnabled(type)) {
            return;
        }

        deliverOrQueue(mp.getServer(), mp.getUniqueID(), type, i18nKey, args, worldTime, summary);
    }

    private static void notifyOfflineInternal(MinecraftServer server, UUID ownerId, NotificationType type,
                                               String i18nKey, String[] args, @Nullable BattleSummary summary) {
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(ownerId);
        if (player != null) {
            notifyInternal(player, type, i18nKey, args, summary);
            return;
        }

        if (!TravellersConfig.NOTIFICATIONS.masterEnabled) {
            return;
        }

        long worldTime = server.getWorld(0).getTotalWorldTime();

        if (type == NotificationType.EVENT) {
            NotificationJournalData.get(server.getWorld(0))
                    .append(ownerId, new JournalEntry(type, i18nKey, args, worldTime, summary));
        }

        if (!isTypeEnabled(type)) {
            return;
        }

        BlockPos loc = summary != null ? summary.location : null;
        int dim = summary != null ? summary.dimension : 0;
        NotificationQueueData.get(server.getWorld(0)).enqueue(ownerId, type, i18nKey, args, worldTime, loc, dim);
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

    private static void deliverOrQueue(MinecraftServer server, UUID ownerId, NotificationType type,
                                        String i18nKey, String[] args, long worldTime, @Nullable BattleSummary summary) {
        BlockPos loc = summary != null ? summary.location : null;
        int dim = summary != null ? summary.dimension : 0;
        EntityPlayerMP p = server.getPlayerList().getPlayerByUUID(ownerId);
        if (p != null) {
            TravellerFollowNet.sendToPlayer(p, new MsgShowNotification(type, i18nKey, args, worldTime, loc, dim));
        } else {
            NotificationQueueData.get(server.getWorld(0)).enqueue(ownerId, type, i18nKey, args, worldTime, loc, dim);
        }
    }
}
