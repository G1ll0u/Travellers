package com.jubitus.travellers.server.map;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MsgSubscribeWorkerMap implements IMessage {
    private boolean subscribe;

    public MsgSubscribeWorkerMap() {}
    public MsgSubscribeWorkerMap(boolean subscribe) {
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

    public static class Handler implements IMessageHandler<MsgSubscribeWorkerMap, IMessage> {
        @Override
        public IMessage onMessage(MsgSubscribeWorkerMap msg, MessageContext ctx) {
            EntityPlayerMP p = ctx.getServerHandler().player;
            p.getServerWorld().addScheduledTask(() -> {
                if (msg.subscribe) {
                    WorkerMapStream.subscribe(p.getUniqueID());
                    BattleMapStream.subscribe(p.getUniqueID());
                    ForeignUnitMapStream.subscribe(p.getUniqueID());
                } else {
                    WorkerMapStream.unsubscribe(p.getUniqueID());
                    BattleMapStream.unsubscribe(p.getUniqueID());
                    ForeignUnitMapStream.unsubscribe(p.getUniqueID());
                }
            });
            return null;
        }
    }
}