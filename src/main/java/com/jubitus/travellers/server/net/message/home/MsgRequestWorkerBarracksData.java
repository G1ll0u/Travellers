package com.jubitus.travellers.server.net.message.home;

import com.jubitus.travellers.server.net.TravellerFollowNet;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** Client -> server: request the barracks-assignment GUI snapshot. */
public class MsgRequestWorkerBarracksData implements IMessage {

    public MsgRequestWorkerBarracksData() {}

    @Override public void fromBytes(ByteBuf buf) {}
    @Override public void toBytes(ByteBuf buf) {}

    public static class Handler implements IMessageHandler<MsgRequestWorkerBarracksData, IMessage> {
        @Override
        public IMessage onMessage(MsgRequestWorkerBarracksData msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() ->
                    TravellerFollowNet.CHANNEL.sendTo(MsgWorkerBarracksData.buildFor(player), player));
            return null;
        }
    }
}
