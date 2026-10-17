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
 * for journaled defs, appends to that player's persistent journal. Offline owners
 * (only a UUID known, e.g. from offscreen/virtual systems) get their notice queued for delivery
 * on next login instead. Which severities show as toasts is a client preference.
 */
public final class NotificationService {

    private NotificationService() {
    }

    public static void notify(EntityPlayer player, NotificationDef def, String... args) {
        if (player instanceof EntityPlayerMP) {
            dispatch(((EntityPlayerMP) player).getServer(), player.getUniqueID(), def.type, def.journaled,
                    def.i18nKey, args, null, null, null, null);
        }
    }

    /** Same as {@link #notify}, located: the toast says where, the journal row centers the map there. */
    public static void notifyAt(EntityPlayer player, NotificationDef def, @Nullable NoticePlace place, String... args) {
        if (player instanceof EntityPlayerMP) {
            dispatch(((EntityPlayerMP) player).getServer(), player.getUniqueID(), def.type, def.journaled,
                    def.i18nKey, args, null, place, null, null);
        }
    }

    public static void notifyOffline(MinecraftServer server, UUID ownerId, NotificationDef def, String... args) {
        dispatch(server, ownerId, def.type, def.journaled, def.i18nKey, args, null, null, null, null);
    }

    /** Same as {@link #notifyOffline}, located (the toast says where, the journal row centers the map there), with an optional second line. */
    public static void notifyOfflineAt(MinecraftServer server, UUID ownerId, NotificationDef def,
                                       @Nullable NoticePlace place, @Nullable NoticeLine detail, String... args) {
        dispatch(server, ownerId, def.type, def.journaled, def.i18nKey, args, null, place, detail, null);
    }

    /**
     * One beat of an ongoing story (a battle): the toast with the same story key is updated in place
     * instead of stacking a new one, and the journal keeps a single row per story, moved to the bottom.
     */
    public static void notifyStory(MinecraftServer server, UUID ownerId, String storyKey, NotificationDef def,
                                   @Nullable BattleSummary summary, @Nullable NoticePlace place,
                                   @Nullable NoticeLine detail, String... args) {
        dispatch(server, ownerId, def.type, def.journaled, def.i18nKey, args, summary, place, detail, storyKey);
    }

    /**
     * For a dynamically-resolved i18n key that isn't (and can't be) a fixed {@link NotificationDef}
     * constant - e.g. a vanilla death-message key forwarded verbatim via {@link VanillaDeathMessage}.
     */
    public static void notifyDangerOfflineRaw(MinecraftServer server, UUID ownerId, String i18nKey,
                                              @Nullable NoticePlace place, @Nullable NoticeLine detail, String... args) {
        dispatch(server, ownerId, NotificationType.DANGER, true, i18nKey, args, null, place, detail, null);
    }

    private static void dispatch(@Nullable MinecraftServer server, UUID ownerId, NotificationType type, boolean journaled,
                                 String i18nKey, String[] args, @Nullable BattleSummary summary,
                                 @Nullable NoticePlace place, @Nullable NoticeLine detail, @Nullable String storyKey) {
        if (server == null || ownerId == null) return;
        if (!TravellersConfig.NOTIFICATIONS.masterEnabled) return;

        if (place == null && summary != null) {
            place = new NoticePlace(summary.location, summary.dimension, null);
        }
        long worldTime = server.getWorld(0).getTotalWorldTime();
        EntityPlayerMP online = server.getPlayerList().getPlayerByUUID(ownerId);

        // Journaled whatever the player's toast preferences, so the history stays complete.
        if (journaled) {
            JournalEntry entry = new JournalEntry(type, i18nKey, args, worldTime, summary, place, detail, storyKey);
            NotificationJournalData.get(server.getWorld(0)).append(ownerId, entry);
            if (online != null) TravellersNetwork.sendToPlayer(online, new MsgJournalEntryAdded(entry));
        }

        if (online != null) {
            TravellersNetwork.sendToPlayer(online, new MsgShowNotification(type, i18nKey, args, worldTime, place, detail, storyKey));
        } else {
            NotificationQueueData.get(server.getWorld(0)).enqueue(ownerId, type, i18nKey, args, worldTime, place, detail, storyKey);
        }
    }

    /** The latest journal row of a story, or null (lets a story build on its own previous beat, e.g. the casualty list). */
    @Nullable
    public static JournalEntry storyEntry(MinecraftServer server, UUID ownerId, String storyKey) {
        return NotificationJournalData.get(server.getWorld(0)).findStory(ownerId, storyKey);
    }
}
