package com.jubitus.traveller.traveller.utils.debug;

import com.jubitus.traveller.traveller.utils.sound.MsgPlayTravellerVoice;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

public final class TravellerFollowNet {
    private TravellerFollowNet() {}

    public static final SimpleNetworkWrapper CHANNEL =
            NetworkRegistry.INSTANCE.newSimpleChannel("travfollow");

    private static boolean registered = false;
    private static int NEXT_ID = 0;

    public static void init() {
        if (registered) return;

        // toggle follow (example)
        registerBoth(MsgToggleFollow.class, new MsgToggleFollow.Handler(), new MsgToggleFollow.NoopHandler());

        // moving voice
        registerBoth(MsgPlayTravellerVoice.class, new MsgPlayTravellerVoice.Handler(), new MsgPlayTravellerVoice.NoopServer());

        registered = true;
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private static void registerBoth(Class<? extends IMessage> msgType,
                                     IMessageHandler<?, ?> clientHandler,
                                     IMessageHandler<?, ?> serverHandler) {
        int id = NEXT_ID++;
        CHANNEL.registerMessage((IMessageHandler) clientHandler, (Class) msgType, id, Side.CLIENT);
        CHANNEL.registerMessage((IMessageHandler) serverHandler, (Class) msgType, id, Side.SERVER);
    }
    public static void sendToPlayer(net.minecraft.entity.player.EntityPlayer player, net.minecraftforge.fml.common.network.simpleimpl.IMessage msg) {
        if (player instanceof net.minecraft.entity.player.EntityPlayerMP) {
            CHANNEL.sendTo(msg, (net.minecraft.entity.player.EntityPlayerMP) player);
        }
    }
    public static class NoopServer implements IMessageHandler<MsgPlayTravellerVoice, IMessage> {
        @Override public IMessage onMessage(MsgPlayTravellerVoice msg, MessageContext ctx) { return null; }
    }

}



