package com.jubitus.travellers.worldmap.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import com.jubitus.travellers.worldmap.stream.CampMapStream;

public class MsgSubscribeCampMap implements IMessage {
    private boolean subscribe;

    public MsgSubscribeCampMap() {}
    public MsgSubscribeCampMap(boolean subscribe) {
        this.subscribe = subscribe;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        subscribe = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(subscribe);
    }

    public static class Handler implements IMessageHandler<MsgSubscribeCampMap, IMessage> {
        @Override
        public IMessage onMessage(MsgSubscribeCampMap msg, MessageContext ctx) {
            EntityPlayerMP p = ctx.getServerHandler().player;
            p.getServerWorld().addScheduledTask(() -> {
                if (msg.subscribe) {
                    CampMapStream.subscribe(p.getUniqueID());
                } else {
                    CampMapStream.unsubscribe(p.getUniqueID());
                }
            });
            return null;
        }
    }
}
