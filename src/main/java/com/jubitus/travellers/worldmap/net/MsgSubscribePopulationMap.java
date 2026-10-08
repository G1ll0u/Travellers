package com.jubitus.travellers.worldmap.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import com.jubitus.travellers.worldmap.stream.PopulationMapStream;
import com.jubitus.travellers.worldmap.stream.BattleMapStream;

public class MsgSubscribePopulationMap implements IMessage {
    private boolean subscribe;

    public MsgSubscribePopulationMap() {}
    public MsgSubscribePopulationMap(boolean subscribe) {
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

    public static class Handler implements IMessageHandler<MsgSubscribePopulationMap, IMessage> {
        @Override
        public IMessage onMessage(MsgSubscribePopulationMap msg, MessageContext ctx) {
            EntityPlayerMP p = ctx.getServerHandler().player;
            if (msg.subscribe && !com.jubitus.travellers.debug.DebugAccess.allowed(p)) return null;
            p.getServerWorld().addScheduledTask(() -> {
                if (msg.subscribe) {
                    PopulationMapStream.subscribe(p.getUniqueID());
                    BattleMapStream.subscribe(p.getUniqueID());
                } else {
                    PopulationMapStream.unsubscribe(p.getUniqueID());
                    BattleMapStream.unsubscribe(p.getUniqueID());
                }
            });
            return null;
        }
    }
}
