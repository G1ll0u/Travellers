package com.jubitus.travellers.server.net.message.workerplayer;

import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.MsgOpenWorkerSubGui;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** Client -> server: "send me my current account-wide declared-enemy list" (GuiWorkerEnemies on open). */
public class MsgRequestPlayerHostileList implements IMessage {

    public MsgRequestPlayerHostileList() {
    }

    @Override
    public void fromBytes(ByteBuf buf) {
    }

    @Override
    public void toBytes(ByteBuf buf) {
    }

    public static class Handler implements IMessageHandler<MsgRequestPlayerHostileList, IMessage> {
        @Override
        public IMessage onMessage(MsgRequestPlayerHostileList msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() ->
                    TravellerFollowNet.CHANNEL.sendTo(MsgOpenWorkerSubGui.buildHostileListPacket(player), player)
            );
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgRequestPlayerHostileList, IMessage> {
        @Override
        public IMessage onMessage(MsgRequestPlayerHostileList msg, MessageContext ctx) {
            return null;
        }
    }
}
