package com.jubitus.travellers.server.net.message.map;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.server.map.MapDiscoverySavedData;
import com.jubitus.travellers.server.map.MapPyramidStore;
import com.jubitus.travellers.server.map.MapTileGenerator;
import com.jubitus.travellers.server.map.TileKey;
import com.jubitus.travellers.server.map.WorldMapDiscovery;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import io.netty.buffer.ByteBuf;
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

        @Override
        public IMessage onMessage(MsgRequestMapTiles msg, MessageContext ctx) {
            EntityPlayerMP p = ctx.getServerHandler().player;
            int level = Math.max(0, Math.min(msg.level, com.jubitus.travellers.server.map.PyramidDirtyPropagator.MAX_LEVEL));
            long[] keys = msg.tileKeys;

            p.getServerWorld().addScheduledTask(() -> {
                Set<Long> discovered = MapDiscoverySavedData.get(p.world).getTiles(p.getUniqueID());
                boolean fullMap = TravellersConfig.RENDERING.showFullMap;

                MapPyramidStore store = MapPyramidStore.get(p.world, level);

                int pcx = ((int) Math.floor(p.posX)) >> 4;
                int pcz = ((int) Math.floor(p.posZ)) >> 4;
                int pTileX = TileKey.floorDiv(pcx, WorldMapDiscovery.CHUNKS_PER_TILE);
                int pTileZ = TileKey.floorDiv(pcz, WorldMapDiscovery.CHUNKS_PER_TILE);

                int sent = 0;
                for (int i = 0; i < keys.length && sent < MAX_KEYS_PER_REQUEST; i++) {
                    long key = keys[i];
                    int tx = TileKey.unpackX(key);
                    int tz = TileKey.unpackZ(key);

                    if (!fullMap && !discovered.contains(key)) continue;

                    byte[] data = store.get(key);
                    boolean had = (data != null && data.length == MapTileGenerator.TILE_BYTES);
                    if (!had) data = new byte[MapTileGenerator.TILE_BYTES];

                    // Only level 0 ever samples the world, and only near the requesting
                    // player's own position (prevents heavy generation work when panning far).
                    boolean changed = false;
                    if (level == 0) {
                        boolean nearPlayer = Math.abs(tx - pTileX) <= 1 && Math.abs(tz - pTileZ) <= 1;
                        if (nearPlayer) {
                            changed = MapTileGenerator.fillMissingPixels(p.world, tx, tz, data, Integer.MAX_VALUE);
                        }
                    }

                    if (!had || changed) {
                        store.put(key, data);
                    }
                    if (level == 0 && changed) {
                        com.jubitus.travellers.server.map.PyramidDirtyPropagator.markParentDirty(
                                p.world.provider.getDimension(), 0, tx, tz);
                    }

                    if (!had && level > 0) continue; // don't push still-empty derived tiles

                    byte[] compressed = com.jubitus.travellers.server.map.TileCompression.deflate(data);
                    TravellerFollowNet.CHANNEL.sendTo(new MsgMapTileData(level, tx, tz, compressed), p);
                    sent++;
                }
            });
            return null;
        }
    }
}
