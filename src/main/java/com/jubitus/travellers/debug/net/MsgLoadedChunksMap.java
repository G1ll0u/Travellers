package com.jubitus.travellers.debug.net;

import com.jubitus.travellers.debug.client.ClientLoadedChunksOverlay;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** Debug: which chunks of a rectangle are loaded (row-major bitset, row 0 = north). Reply to {@link MsgLoadedChunksRequest}. */
public class MsgLoadedChunksMap implements IMessage {

    public int minChunkX;
    public int minChunkZ;
    public int wChunks;
    public int hChunks;
    public byte[] bits;

    public MsgLoadedChunksMap() {
    }

    public MsgLoadedChunksMap(int minChunkX, int minChunkZ, int wChunks, int hChunks, byte[] bits) {
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.wChunks = wChunks;
        this.hChunks = hChunks;
        this.bits = bits;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        minChunkX = buf.readInt();
        minChunkZ = buf.readInt();
        wChunks = buf.readInt();
        hChunks = buf.readInt();
        bits = new byte[(wChunks * hChunks + 7) / 8];
        buf.readBytes(bits);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(minChunkX);
        buf.writeInt(minChunkZ);
        buf.writeInt(wChunks);
        buf.writeInt(hChunks);
        buf.writeBytes(bits);
    }

    @SideOnly(Side.CLIENT)
    public static class ClientHandler implements IMessageHandler<MsgLoadedChunksMap, IMessage> {
        @Override
        public IMessage onMessage(MsgLoadedChunksMap msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() ->
                    ClientLoadedChunksOverlay.accept(msg.minChunkX, msg.minChunkZ, msg.wChunks, msg.hChunks, msg.bits));
            return null;
        }
    }
}
