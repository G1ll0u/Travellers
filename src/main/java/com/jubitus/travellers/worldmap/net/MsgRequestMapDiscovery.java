package com.jubitus.travellers.worldmap.net;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.worldmap.pyramid.MapDiscoverySavedData;
import com.jubitus.travellers.worldmap.stream.RoadMapSync;
import com.jubitus.travellers.net.TravellersNetwork;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MsgRequestMapDiscovery implements IMessage {
    public MsgRequestMapDiscovery() {}

    @Override public void fromBytes(ByteBuf buf) {}
    @Override public void toBytes(ByteBuf buf) {}

    public static class Handler implements IMessageHandler<MsgRequestMapDiscovery, IMessage> {
        @Override
        public IMessage onMessage(MsgRequestMapDiscovery msg, MessageContext ctx) {
            EntityPlayerMP p = ctx.getServerHandler().player;
            p.getServerWorld().addScheduledTask(() -> {
                MapDiscoverySavedData saved = MapDiscoverySavedData.get(p.world);
                TravellersNetwork.CHANNEL.sendTo(new MsgMapDiscoverySync(saved.getMasks(p.getUniqueID()),
                        TravellersConfig.WORLDMAP.showFullMap), p);
                RoadMapSync.onMapOpened(p);
            });
            return null;
        }
    }
}
