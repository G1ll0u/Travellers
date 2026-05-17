package com.jubitus.travellers.server.net.message.map;

import com.jubitus.travellers.server.map.WorldMapDiscovery;
import com.jubitus.travellers.server.map.MapDiscoverySavedData;
import com.jubitus.travellers.server.map.MapTileGenerator;
import com.jubitus.travellers.server.map.MapTileImageSavedData;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.Set;

public class MsgRequestMapTiles implements IMessage {
    public int minTileX, maxTileX, minTileZ, maxTileZ;

    public MsgRequestMapTiles() {}

    public MsgRequestMapTiles(int minTileX, int maxTileX, int minTileZ, int maxTileZ) {
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

    public static class Handler implements IMessageHandler<MsgRequestMapTiles, IMessage> {
        @Override
        public IMessage onMessage(MsgRequestMapTiles msg, MessageContext ctx) {
            EntityPlayerMP p = ctx.getServerHandler().player;
            p.getServerWorld().addScheduledTask(() -> {

                // tuiles découvertes du joueur
                Set<Long> discovered = MapDiscoverySavedData.get(p.world).getTiles(p.getUniqueID());

                // images stockées world-wide
                MapTileImageSavedData img = MapTileImageSavedData.get(p.world);

                // Limite sécurité anti-spam
                int sent = 0;
                int maxSend = 64; // max 64 tuiles par requête

                for (int tz = msg.minTileZ; tz <= msg.maxTileZ; tz++) {
                    for (int tx = msg.minTileX; tx <= msg.maxTileX; tx++) {
                        if (sent >= maxSend) return;

                        long key = pack(tx, tz);

                        // on envoie seulement si découvert
                        if (!discovered.contains(key)) continue;

                        byte[] data = img.get(key);
                        boolean had = (data != null && data.length == MapTileImageSavedData.TILE_BYTES);

                        if (!had) {
                            data = new byte[MapTileImageSavedData.TILE_BYTES]; // all 0 = unknown
                        }

// Only try to fill/generate tiles that are near the PLAYER (prevents heavy work when panning far)
                        int pcx = ((int) Math.floor(p.posX)) >> 4;
                        int pcz = ((int) Math.floor(p.posZ)) >> 4;
                        int pTileX = floorDiv(pcx, WorldMapDiscovery.CHUNKS_PER_TILE);
                        int pTileZ = floorDiv(pcz, WorldMapDiscovery.CHUNKS_PER_TILE);

                        boolean nearPlayer = Math.abs(tx - pTileX) <= 1 && Math.abs(tz - pTileZ) <= 1;

                        boolean changed = false;
                        if (nearPlayer) {
                            changed = MapTileGenerator.fillMissingPixels(p.world, tx, tz, data);
                        }

                        if (!had || changed) {
                            img.put(key, data);
                        }

// Always send what we have (even if mostly unknown); client will draw unknown pixels gray
                        TravellerFollowNet.CHANNEL.sendTo(new MsgMapTileData(tx, tz, data), p);
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
    private static int floorDiv(int a, int b) {
        int r = a / b;
        if ((a ^ b) < 0 && (r * b != a)) r--;
        return r;
    }

}
