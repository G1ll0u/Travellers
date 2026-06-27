package com.jubitus.travellers.server.net.message.map;

import com.jubitus.travellers.client.gui.worldmap.GuiWorldMapPanelHooks;
import com.jubitus.travellers.client.map.ClientDetailTileCache;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public class MsgMapDetailTileData implements IMessage {
    public int tileX, tileZ;
    public byte[] data; // 65536 bytes (256x256)

    public MsgMapDetailTileData() {}

    public MsgMapDetailTileData(int tileX, int tileZ, byte[] data) {
        this.tileX = tileX;
        this.tileZ = tileZ;
        this.data = data;
    }

    @Override public void fromBytes(ByteBuf buf) {
        tileX = buf.readInt();
        tileZ = buf.readInt();
        int n = buf.readInt();
        data = new byte[n];
        buf.readBytes(data);
    }

    @Override public void toBytes(ByteBuf buf) {
        buf.writeInt(tileX);
        buf.writeInt(tileZ);
        buf.writeInt(data.length);
        buf.writeBytes(data);
    }

    @SideOnly(Side.CLIENT)
    public static class ClientHandler implements IMessageHandler<MsgMapDetailTileData, IMessage> {
        @Override
        public IMessage onMessage(MsgMapDetailTileData msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                long key = (((long) msg.tileX) << 32) ^ (msg.tileZ & 0xFFFFFFFFL);
                ClientDetailTileCache.put(key, msg.data);
                GuiWorldMapPanelHooks.markDirty();
            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgMapDetailTileData, IMessage> {
        @Override public IMessage onMessage(MsgMapDetailTileData msg, MessageContext ctx) { return null; }
    }
}
