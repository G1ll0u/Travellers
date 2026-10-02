package com.jubitus.travellers.debug.net;

import com.jubitus.travellers.debug.client.ClientTerrainDebugOverlay;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.nio.ByteBuffer;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Debug: a block of scanned terrain-grid cells as row-major ARGB (row 0 = north), deflated. Drawn as
 * an overlay under the markers on {@code GuiTravellerPopulationMap}. Reply to {@link MsgTerrainDebugRequest}.
 */
public class MsgTerrainDebugMap implements IMessage {

    public int minCellX;
    public int minCellZ;
    public int wCells;
    public int hCells;
    public byte mode;
    public int[] argb;

    public MsgTerrainDebugMap() {
    }

    public MsgTerrainDebugMap(int minCellX, int minCellZ, int wCells, int hCells, byte mode, int[] argb) {
        this.minCellX = minCellX;
        this.minCellZ = minCellZ;
        this.wCells = wCells;
        this.hCells = hCells;
        this.mode = mode;
        this.argb = argb;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        minCellX = buf.readInt();
        minCellZ = buf.readInt();
        wCells = buf.readInt();
        hCells = buf.readInt();
        mode = buf.readByte();
        int n = wCells * hCells;
        int compLen = buf.readInt();
        byte[] comp = new byte[compLen];
        buf.readBytes(comp);
        byte[] raw = new byte[n * 4];
        Inflater inf = new Inflater();
        try {
            inf.setInput(comp);
            int off = 0;
            while (off < raw.length && !inf.finished()) {
                off += inf.inflate(raw, off, raw.length - off);
            }
        } catch (Exception e) {
            argb = new int[n];
            return;
        } finally {
            inf.end();
        }
        ByteBuffer bb = ByteBuffer.wrap(raw);
        argb = new int[n];
        for (int i = 0; i < n; i++) argb[i] = bb.getInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(minCellX);
        buf.writeInt(minCellZ);
        buf.writeInt(wCells);
        buf.writeInt(hCells);
        buf.writeByte(mode);
        byte[] raw = new byte[argb.length * 4];
        ByteBuffer bb = ByteBuffer.wrap(raw);
        for (int c : argb) bb.putInt(c);
        Deflater def = new Deflater(Deflater.BEST_SPEED);
        def.setInput(raw);
        def.finish();
        byte[] out = new byte[raw.length + 64];
        int len = 0;
        while (!def.finished()) {
            if (len == out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
            len += def.deflate(out, len, out.length - len);
        }
        def.end();
        buf.writeInt(len);
        buf.writeBytes(out, 0, len);
    }

    @SideOnly(Side.CLIENT)
    public static class ClientHandler implements IMessageHandler<MsgTerrainDebugMap, IMessage> {
        @Override
        public IMessage onMessage(MsgTerrainDebugMap msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() ->
                    ClientTerrainDebugOverlay.accept(msg.minCellX, msg.minCellZ, msg.wCells, msg.hCells, msg.mode, msg.argb));
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgTerrainDebugMap, IMessage> {
        @Override
        public IMessage onMessage(MsgTerrainDebugMap msg, MessageContext ctx) {
            return null;
        }
    }
}
