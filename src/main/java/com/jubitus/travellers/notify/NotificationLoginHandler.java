package com.jubitus.travellers.notify;

import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.notify.net.MsgShowNotification;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

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
        for (QueuedNotice n : queued) {
            TravellersNetwork.sendToPlayer(player,
                    new MsgShowNotification(n.type, n.i18nKey, n.args, n.worldTime, n.place, n.detail, n.storyKey));
        }
    }
}
