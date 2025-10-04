package com.jubitus.traveller.traveller.utils.debug;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.relauncher.Side;

public final class TravellerFollowNet {
    public static final net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper CHANNEL =
            net.minecraftforge.fml.common.network.NetworkRegistry.INSTANCE.newSimpleChannel("travfollow");
    private static final int id = 0;
    private static boolean registered = false;
    private TravellerFollowNet() {
    }

    public static void init() {
        if (registered) return; // avoid double-register
        // Server will ENCODE and SEND this to client → needs a server-side entry too
        CHANNEL.registerMessage(MsgToggleFollow.Handler.class, MsgToggleFollow.class, id, Side.CLIENT);
        CHANNEL.registerMessage(MsgToggleFollow.NoopHandler.class, MsgToggleFollow.class, id, Side.SERVER);
        registered = true;
    }

    public static void sendToPlayer(EntityPlayer player, IMessage msg) {
        if (!(player instanceof EntityPlayerMP)) return;
        CHANNEL.sendTo(msg, (EntityPlayerMP) player);
    }

}

