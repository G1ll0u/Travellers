package com.jubitus.travellers.follow.net;

import com.jubitus.travellers.follow.client.PlayerFollowClient;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** Server -> client: the unit this player now follows hands-off ({@link #NONE} = no session). */
public class MsgPlayerFollowState implements IMessage {

    public static final int NONE = -1;

    private int entityId = NONE;

    public MsgPlayerFollowState() {
    }

    public MsgPlayerFollowState(int entityId) {
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

    public static class Handler implements IMessageHandler<MsgPlayerFollowState, IMessage> {
        @Override
        public IMessage onMessage(MsgPlayerFollowState msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> PlayerFollowClient.onServerState(msg.entityId));
            return null;
        }
    }
}
