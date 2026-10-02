package com.jubitus.travellers.worldmap.net;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.worldmap.pyramid.MapDiscoverySavedData;
import com.jubitus.travellers.worldmap.pyramid.MapPyramidStore;
import com.jubitus.travellers.worldmap.pyramid.MapTileGenerator;
import com.jubitus.travellers.worldmap.pyramid.TileKey;
import com.jubitus.travellers.net.TravellerFollowNet;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.Set;

/**
 * Client asks for exactly the tile keys it's missing at a given pyramid level (computed by
 * diffing its own cache against the current view) — replaces the old "re-request the whole
 * viewport bounding box every poll" behavior, which wasted bandwidth on tiles the client
 * already had cached.
 */
public class MsgRequestMapTiles implements IMessage {
    public int level;
    public long[] tileKeys;

    public MsgRequestMapTiles() {}

    public MsgRequestMapTiles(int level, long[] tileKeys) {
        this.level = level;
        this.tileKeys = tileKeys;
    }

    @Override public void fromBytes(ByteBuf buf) {
        level = buf.readInt();
        int n = buf.readInt();
        tileKeys = new long[n];
        for (int i = 0; i < n; i++) tileKeys[i] = buf.readLong();
    }

    @Override public void toBytes(ByteBuf buf) {
        buf.writeInt(level);
        buf.writeInt(tileKeys.length);
        for (long k : tileKeys) buf.writeLong(k);
    }

    public static class Handler implements IMessageHandler<MsgRequestMapTiles, IMessage> {
        private static final int MAX_KEYS_PER_REQUEST = 64;
        private static final byte[] EMPTY_TILE =
                com.jubitus.travellers.worldmap.pyramid.TileCompression.deflate(new byte[MapTileGenerator.TILE_BYTES]);

        @Override
        public IMessage onMessage(MsgRequestMapTiles msg, MessageContext ctx) {
            EntityPlayerMP p = ctx.getServerHandler().player;
            int level = Math.max(0, Math.min(msg.level, com.jubitus.travellers.worldmap.pyramid.PyramidDirtyPropagator.MAX_LEVEL));
            long[] keys = msg.tileKeys;

            p.getServerWorld().addScheduledTask(() -> {
                if (!MapPyramidStore.isMapped(p.world)) return;
                boolean fullMap = TravellersConfig.WORLDMAP.showFullMap;
                LongOpenHashSet discovered = null;
                if (!fullMap) {
                    // A tile at this level is served if any level-0 tile inside it has a discovered chunk.
                    discovered = new LongOpenHashSet();
                    int span = 1 << level;
                    for (LongIterator it = MapDiscoverySavedData.get(p.world).getMasks(p.getUniqueID()).keySet().iterator(); it.hasNext(); ) {
                        long k0 = it.nextLong();
                        discovered.add(TileKey.pack(Math.floorDiv(TileKey.unpackX(k0), span), Math.floorDiv(TileKey.unpackZ(k0), span)));
                    }
                }

                MapPyramidStore store = MapPyramidStore.get(p.world, level);

                int sent = 0;
                for (int i = 0; i < keys.length && sent < MAX_KEYS_PER_REQUEST; i++) {
                    long key = keys[i];
                    int tx = TileKey.unpackX(key);
                    int tz = TileKey.unpackZ(key);

                    if (discovered != null && !discovered.contains(key)) continue;

                    // Tiles are painted by ChunkReader as chunks load; a request only serves the store.
                    // A level-0 tile with nothing painted yet still gets an empty reply.
                    byte[] compressed = store.getCompressed(key);
                    if (compressed == null) {
                        if (level > 0) continue;
                        compressed = EMPTY_TILE;
                    }
                    TravellerFollowNet.CHANNEL.sendTo(new MsgMapTileData(level, tx, tz, compressed), p);
                    sent++;
                }
            });
            return null;
        }
    }
}
