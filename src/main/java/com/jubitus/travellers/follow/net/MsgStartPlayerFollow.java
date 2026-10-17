package com.jubitus.travellers.follow.net;

import com.jubitus.travellers.follow.PlayerFollowSessions;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** Client -> server: the player asks to follow this unit hands-off. */
public class MsgStartPlayerFollow implements IMessage {

    private int entityId;

    public MsgStartPlayerFollow() {
    }

    public MsgStartPlayerFollow(int entityId) {
        this.entityId = entityId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.entityId = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(entityId);
    }

    public static class Handler implements IMessageHandler<MsgStartPlayerFollow, IMessage> {
        @Override
        public IMessage onMessage(MsgStartPlayerFollow msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> PlayerFollowSessions.start(player, msg.entityId));
            return null;
        }
    }
}
