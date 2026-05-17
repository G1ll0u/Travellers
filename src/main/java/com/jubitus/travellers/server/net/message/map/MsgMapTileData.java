package com.jubitus.travellers.server.net.message.map;

import com.jubitus.travellers.client.gui.worldmap.GuiWorldMapPanelHooks;
import com.jubitus.travellers.client.map.ClientMapTileCache;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public class MsgMapTileData implements IMessage {
    public int tileX, tileZ;
    public byte[] data; // 4096 bytes (64x64)

    public MsgMapTileData() {}

    public MsgMapTileData(int tileX, int tileZ, byte[] data) {
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
    public static class ClientHandler implements IMessageHandler<MsgMapTileData, IMessage> {
        @Override
        public IMessage onMessage(MsgMapTileData msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                long key = (((long) msg.tileX) << 32) ^ (msg.tileZ & 0xFFFFFFFFL);
                ClientMapTileCache.put(key, msg.data);
                GuiWorldMapPanelHooks.markDirty();
            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgMapTileData, IMessage> {
        @Override public IMessage onMessage(MsgMapTileData msg, MessageContext ctx) { return null; }
    }
}
