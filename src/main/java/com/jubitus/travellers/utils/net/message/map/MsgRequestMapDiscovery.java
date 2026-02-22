package com.jubitus.travellers.utils.net.message.map;

import com.jubitus.travellers.server.map.MapDiscoverySavedData;
import com.jubitus.travellers.utils.net.TravellerFollowNet;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.Set;

public class MsgRequestMapDiscovery implements IMessage {
    public MsgRequestMapDiscovery() {}

    @Override public void fromBytes(ByteBuf buf) {}
    @Override public void toBytes(ByteBuf buf) {}

    public static class Handler implements IMessageHandler<MsgRequestMapDiscovery, IMessage> {
        @Override
        public IMessage onMessage(MsgRequestMapDiscovery msg, MessageContext ctx) {
            EntityPlayerMP p = ctx.getServerHandler().player;
            p.getServerWorld().addScheduledTask(() -> {
                // récupère les tiles découvertes du joueur (mémoire serveur)
                Set<Long> tiles = MapDiscoverySavedData.get(p.world).getTiles(p.getUniqueID());
                TravellerFollowNet.CHANNEL.sendTo(new MsgMapDiscoverySync(tiles), p);
            });
            return null;
        }
    }
}
