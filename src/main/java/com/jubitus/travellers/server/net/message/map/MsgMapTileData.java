package com.jubitus.travellers.server.net.message.map;

import com.jubitus.travellers.client.map.ClientTileTextures;
import com.jubitus.travellers.client.map.ClientTileCache;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.server.map.pyramid.MapTileGenerator;
import com.jubitus.travellers.server.map.pyramid.TileCompression;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.logging.log4j.Level;

public class MsgMapTileData implements IMessage {
    public int level, tileX, tileZ;
    public byte[] compressedData;

    public MsgMapTileData() {}

    public MsgMapTileData(int level, int tileX, int tileZ, byte[] compressedData) {
        this.level = level;
        this.tileX = tileX;
        this.tileZ = tileZ;
        this.compressedData = compressedData;
    }

    @Override public void fromBytes(ByteBuf buf) {
        level = buf.readInt();
        tileX = buf.readInt();
        tileZ = buf.readInt();
        int n = buf.readInt();
        compressedData = new byte[n];
        buf.readBytes(compressedData);
    }

    @Override public void toBytes(ByteBuf buf) {
        buf.writeInt(level);
        buf.writeInt(tileX);
        buf.writeInt(tileZ);
        buf.writeInt(compressedData.length);
        buf.writeBytes(compressedData);
    }

    @SideOnly(Side.CLIENT)
    public static class ClientHandler implements IMessageHandler<MsgMapTileData, IMessage> {
        @Override
        public IMessage onMessage(MsgMapTileData msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                byte[] data = TileCompression.inflate(msg.compressedData, MapTileGenerator.TILE_BYTES);
                ClientTileCache.put(msg.level, msg.tileX, msg.tileZ, data);
                ClientTileTextures.invalidate(msg.level, msg.tileX, msg.tileZ);
                if (TravellersConfig.WORLDMAP.debugMap) {
                    FMLLog.log(Level.INFO, "[Travellers/MapNet] client: tile data received, cached level=%d tile=%d,%d",
                            msg.level, msg.tileX, msg.tileZ);
                }
            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgMapTileData, IMessage> {
        @Override public IMessage onMessage(MsgMapTileData msg, MessageContext ctx) { return null; }
    }
}
