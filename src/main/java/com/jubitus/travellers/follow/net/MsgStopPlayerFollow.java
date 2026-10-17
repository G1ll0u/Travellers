package com.jubitus.travellers.follow.net;

import com.jubitus.travellers.follow.PlayerFollowSessions;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** Client -> server: the player ended the hands-off follow (key pressed, danger, arrival). */
public class MsgStopPlayerFollow implements IMessage {

    @Override
    public void fromBytes(ByteBuf buf) {
    }

    @Override
    public void toBytes(ByteBuf buf) {
    }

    public static class Handler implements IMessageHandler<MsgStopPlayerFollow, IMessage> {
        @Override
        public IMessage onMessage(MsgStopPlayerFollow msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> PlayerFollowSessions.stop(player));
            return null;
        }
    }
}
