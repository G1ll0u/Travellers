package com.jubitus.travellers.notify;

import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.notify.net.MsgShowNotification;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Delivers notifications queued while the owner was offline. Queue is global (one instance
 * world-wide, see {@link NotificationQueueData#get}), so no per-dimension loop is needed here. */
@Mod.EventBusSubscriber(modid = "travellers")
public final class NotificationLoginHandler {

    private NotificationLoginHandler() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;

        List<QueuedNotice> queued = NotificationQueueData.get(player.world).take(player.getUniqueID());
        List<QueuedNotice> battles = new ArrayList<>();
        for (QueuedNotice n : queued) {
            if (BattleNotifications.isStory(n.storyKey)) battles.add(n);
        }
        // Several battles while away: one summary toast, each battle stays in the journal.
        boolean summarize = battles.size() > 1;
        for (QueuedNotice n : queued) {
            if (summarize && battles.contains(n)) continue;
            send(player, n.type, n.i18nKey, n.args, n.worldTime, n.place, n.detail, n.storyKey);
        }
        if (summarize) {
            int fallen = 0;
            long latest = 0;
            for (QueuedNotice n : battles) {
                fallen += fallenIn(n);
                latest = Math.max(latest, n.worldTime);
            }
            NotificationDef def = NotificationDefs.BATTLE_AWAY_SUMMARY;
            send(player, def.type, def.i18nKey, new String[]{String.valueOf(battles.size()), String.valueOf(fallen)},
                    latest, null, null, null);
        }
    }

    /** Henchmen fallen as told by a battle story's last beat (its first argument, absent on the opening beat). */
    private static int fallenIn(QueuedNotice n) {
        if (NotificationDefs.BATTLE_UNDER_ATTACK.i18nKey.equals(n.i18nKey) || n.args.length == 0) return 0;
        try {
            return Integer.parseInt(n.args[0]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void send(EntityPlayerMP player, NotificationType type, String key, String[] args, long worldTime,
                             @Nullable NoticePlace place, @Nullable NoticeLine detail, @Nullable String storyKey) {
        TravellersNetwork.sendToPlayer(player, new MsgShowNotification(type, key, args, worldTime, place, detail, storyKey));
    }
}
