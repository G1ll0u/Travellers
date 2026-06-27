package com.jubitus.travellers.server.net.message.map;

import com.jubitus.travellers.server.map.MapDetailTileImageSavedData;
import com.jubitus.travellers.server.map.MapDiscoverySavedData;
import com.jubitus.travellers.server.map.MapTileGenerator;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.Set;

public class MsgRequestDetailTiles implements IMessage {
    public int minTileX, maxTileX, minTileZ, maxTileZ;

    public MsgRequestDetailTiles() {}

    public MsgRequestDetailTiles(int minTileX, int maxTileX, int minTileZ, int maxTileZ) {
        this.minTileX = minTileX;
        this.maxTileX = maxTileX;
        this.minTileZ = minTileZ;
        this.maxTileZ = maxTileZ;
    }

    @Override public void fromBytes(ByteBuf buf) {
        minTileX = buf.readInt();
        maxTileX = buf.readInt();
        minTileZ = buf.readInt();
        maxTileZ = buf.readInt();
    }

    @Override public void toBytes(ByteBuf buf) {
        buf.writeInt(minTileX);
        buf.writeInt(maxTileX);
        buf.writeInt(minTileZ);
        buf.writeInt(maxTileZ);
    }

    public static class Handler implements IMessageHandler<MsgRequestDetailTiles, IMessage> {
        @Override
        public IMessage onMessage(MsgRequestDetailTiles msg, MessageContext ctx) {
            EntityPlayerMP p = ctx.getServerHandler().player;
            p.getServerWorld().addScheduledTask(() -> {
                Set<Long> discovered = MapDiscoverySavedData.get(p.world).getTiles(p.getUniqueID());
                MapDetailTileImageSavedData detail = MapDetailTileImageSavedData.get(p.world);

                // Serves only pre-generated data — no chunk loading, no generation here
                int sent = 0;
                final int maxSend = 16; // 16 × 64KB = 1MB max per request

                for (int tz = msg.minTileZ; tz <= msg.maxTileZ && sent < maxSend; tz++) {
                    for (int tx = msg.minTileX; tx <= msg.maxTileX && sent < maxSend; tx++) {
                        long key = pack(tx, tz);
                        if (!discovered.contains(key)) continue;
                        byte[] data = detail.get(key);
                        if (data == null || data.length != MapTileGenerator.DETAIL_BYTES) continue;
                        TravellerFollowNet.CHANNEL.sendTo(new MsgMapDetailTileData(tx, tz, data), p);
                        sent++;
                    }
                }
            });
            return null;
        }

        private static long pack(int x, int z) {
            return (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
        }
    }
}
