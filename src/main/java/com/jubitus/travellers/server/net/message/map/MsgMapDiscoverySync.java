package com.jubitus.travellers.server.net.message.map;

import com.jubitus.travellers.client.map.ClientMapDiscovery;
import com.jubitus.travellers.server.map.pyramid.MapDiscoverySavedData;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** A player's whole fog of war: tile keys with their chunk masks (a fully discovered tile costs one flag). */
public class MsgMapDiscoverySync implements IMessage {
    public long[] keys;
    public long[][] masks;
    public boolean fullMap;

    public MsgMapDiscoverySync() {}

    public MsgMapDiscoverySync(Long2ObjectMap<long[]> tiles, boolean fullMap) {
        this.keys = new long[tiles.size()];
        this.masks = new long[tiles.size()][];
        int i = 0;
        for (Long2ObjectMap.Entry<long[]> e : tiles.long2ObjectEntrySet()) {
            keys[i] = e.getLongKey();
            masks[i] = e.getValue().clone();
            i++;
        }
        this.fullMap = fullMap;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int n = buf.readInt();
        keys = new long[n];
        masks = new long[n][];
        for (int i = 0; i < n; i++) {
            keys[i] = buf.readLong();
            if (buf.readBoolean()) {
                masks[i] = MapDiscoverySavedData.fullMask();
            } else {
                long[] m = new long[MapDiscoverySavedData.MASK_LONGS];
                for (int j = 0; j < m.length; j++) m[j] = buf.readLong();
                masks[i] = m;
            }
        }
        fullMap = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(keys.length);
        for (int i = 0; i < keys.length; i++) {
            buf.writeLong(keys[i]);
            boolean full = MapDiscoverySavedData.isFull(masks[i]);
            buf.writeBoolean(full);
            if (!full) for (long l : masks[i]) buf.writeLong(l);
        }
        buf.writeBoolean(fullMap);
    }

    public static class ClientHandler implements IMessageHandler<MsgMapDiscoverySync, IMessage> {
        @Override
        public IMessage onMessage(MsgMapDiscoverySync msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> ClientMapDiscovery.mergeAll(msg.keys, msg.masks, msg.fullMap));
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgMapDiscoverySync, IMessage> {
        @Override public IMessage onMessage(MsgMapDiscoverySync msg, MessageContext ctx) { return null; }
    }
}
