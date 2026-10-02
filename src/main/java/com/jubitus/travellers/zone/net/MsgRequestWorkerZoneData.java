package com.jubitus.travellers.zone.net;

import com.jubitus.travellers.net.TravellersNetwork;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** Client -> server: request the Duty tab's stable/worker snapshot. */
public class MsgRequestWorkerZoneData implements IMessage {

    public MsgRequestWorkerZoneData() {}

    @Override public void fromBytes(ByteBuf buf) {}
    @Override public void toBytes(ByteBuf buf) {}

    public static class Handler implements IMessageHandler<MsgRequestWorkerZoneData, IMessage> {
        @Override
        public IMessage onMessage(MsgRequestWorkerZoneData msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() ->
                    TravellersNetwork.CHANNEL.sendTo(MsgWorkerZoneData.buildFor(player), player));
            return null;
        }
    }
}
