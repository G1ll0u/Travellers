package com.jubitus.travellers.marker;

import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.marker.net.MsgOwnerVisualsSync;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

/** Pushes the player's own saved mark color/style on login, so the worldmap never has to scavenge it off a worker packet. */
@Mod.EventBusSubscriber(modid = "travellers")
public final class OwnerVisualsLoginHandler {

    private OwnerVisualsLoginHandler() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;

        WorkerOwnerVisualsSavedData.Entry entry = WorkerOwnerVisualsSavedData.get(player.world).getFor(player.getUniqueID());
        TravellersNetwork.sendToPlayer(player, new MsgOwnerVisualsSync(entry.color, entry.styleId));
    }
}
