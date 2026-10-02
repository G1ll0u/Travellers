package com.jubitus.travellers.worldmap.net;

import com.jubitus.travellers.worldmap.client.ClientMapDiscovery;
import com.jubitus.travellers.worldmap.pyramid.MapDiscoverySavedData;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** One level-0 tile's discovered-chunk mask, sent whenever it grows. */
public class MsgMapDiscoverTile implements IMessage {
    public int tileX;
    public int tileZ;
    public long[] mask;

    public MsgMapDiscoverTile() {}

    public MsgMapDiscoverTile(int tileX, int tileZ, long[] mask) {
        this.tileX = tileX;
        this.tileZ = tileZ;
        this.mask = mask.clone();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        tileX = buf.readInt();
        tileZ = buf.readInt();
        mask = new long[MapDiscoverySavedData.MASK_LONGS];
        for (int i = 0; i < mask.length; i++) mask[i] = buf.readLong();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(tileX);
        buf.writeInt(tileZ);
        for (long l : mask) buf.writeLong(l);
    }

    public static class ClientHandler implements IMessageHandler<MsgMapDiscoverTile, IMessage> {
        @Override
        public IMessage onMessage(MsgMapDiscoverTile msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> ClientMapDiscovery.mergeTile(msg.tileX, msg.tileZ, msg.mask));
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgMapDiscoverTile, IMessage> {
        @Override public IMessage onMessage(MsgMapDiscoverTile msg, MessageContext ctx) { return null; }
    }
}
