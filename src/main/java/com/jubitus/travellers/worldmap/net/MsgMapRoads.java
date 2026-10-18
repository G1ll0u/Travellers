package com.jubitus.travellers.worldmap.net;

import com.jubitus.travellers.worldmap.client.ClientMapRoads;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;

/**
 * One part of the Ultreïa road network for the worldmap; the client swaps its roads in once every
 * part of a revision has arrived. Segments are Ultreïa's [kind, width, isBridge, x0, z0, ...] arrays,
 * sent as zigzag varint deltas.
 */
public class MsgMapRoads implements IMessage {
    public long revision;
    public int part;
    public int parts;
    public List<int[]> segments;

    public MsgMapRoads() {}

    public MsgMapRoads(long revision, int part, int parts, List<int[]> segments) {
        this.revision = revision;
        this.part = part;
        this.parts = parts;
        this.segments = segments;
    }

    /** Rough encoded size of one segment, to split the network into parts. */
    public static int estimateBytes(int[] seg) {
        return 16 + (seg.length - 3);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        revision = buf.readLong();
        part = ByteBufUtils.readVarInt(buf, 5);
        parts = ByteBufUtils.readVarInt(buf, 5);
        int n = ByteBufUtils.readVarInt(buf, 5);
        segments = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int kind = buf.readByte();
            int width = buf.readUnsignedByte();
            int bridge = buf.readBoolean() ? 1 : 0;
            int points = ByteBufUtils.readVarInt(buf, 5);
            int[] seg = new int[3 + points * 2];
            seg[0] = kind;
            seg[1] = width;
            seg[2] = bridge;
            int x = 0, z = 0;
            for (int p = 0; p < points; p++) {
                if (p == 0) {
                    x = buf.readInt();
                    z = buf.readInt();
                } else {
                    x += unzigzag(ByteBufUtils.readVarInt(buf, 5));
                    z += unzigzag(ByteBufUtils.readVarInt(buf, 5));
                }
                seg[3 + p * 2] = x;
                seg[4 + p * 2] = z;
            }
            segments.add(seg);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(revision);
        ByteBufUtils.writeVarInt(buf, part, 5);
        ByteBufUtils.writeVarInt(buf, parts, 5);
        ByteBufUtils.writeVarInt(buf, segments.size(), 5);
        for (int[] seg : segments) {
            int points = (seg.length - 3) / 2;
            buf.writeByte(seg[0]);
            buf.writeByte(Math.max(0, Math.min(255, seg[1])));
            buf.writeBoolean(seg[2] != 0);
            ByteBufUtils.writeVarInt(buf, points, 5);
            for (int p = 0; p < points; p++) {
                int x = seg[3 + p * 2], z = seg[4 + p * 2];
                if (p == 0) {
                    buf.writeInt(x);
                    buf.writeInt(z);
                } else {
                    ByteBufUtils.writeVarInt(buf, zigzag(x - seg[1 + p * 2]), 5);
                    ByteBufUtils.writeVarInt(buf, zigzag(z - seg[2 + p * 2]), 5);
                }
            }
        }
    }

    private static int zigzag(int v) {
        return (v << 1) ^ (v >> 31);
    }

    private static int unzigzag(int v) {
        return (v >>> 1) ^ -(v & 1);
    }

    public static class ClientHandler implements IMessageHandler<MsgMapRoads, IMessage> {
        @Override
        public IMessage onMessage(MsgMapRoads msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> ClientMapRoads.accept(msg.revision, msg.part, msg.parts, msg.segments));
            return null;
        }
    }
}
