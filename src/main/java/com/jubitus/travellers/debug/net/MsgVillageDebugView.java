package com.jubitus.travellers.debug.net;

import com.jubitus.travellers.debug.client.ClientVillageDebugOverlay;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;

/** Server -> client: the Millénaire buildings around a player as the mod reads them, for the in-world
 * debug view ({@code /travellersdebug village show}). An empty, inactive message clears it. */
public class MsgVillageDebugView implements IMessage {

    public static final byte POINT_ENTRANCE = 0;
    public static final byte POINT_LEISURE = 1;
    public static final byte POINT_SELLING = 2;
    public static final byte POINT_BED = 3;
    public static final byte POINT_PANEL = 4;

    private static final int MAX_BUILDINGS = 512;
    private static final int MAX_POINTS = 64;

    public boolean active;
    public int labelDistance;
    public List<Building> buildings = new ArrayList<>();

    public static final class Building {
        public int minX, minY, minZ, maxX, maxY, maxZ;
        public String planKey = "";
        public String name = "";
        public String tags = "";
        public String village = "";
        public boolean townHall;
        public List<int[]> points = new ArrayList<>(); // {kind, x, y, z}
    }

    public MsgVillageDebugView() {
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(active);
        if (!active) return;
        buf.writeShort(labelDistance);
        int n = Math.min(MAX_BUILDINGS, buildings.size());
        buf.writeShort(n);
        for (int i = 0; i < n; i++) {
            Building b = buildings.get(i);
            buf.writeInt(b.minX);
            buf.writeInt(b.minY);
            buf.writeInt(b.minZ);
            buf.writeInt(b.maxX);
            buf.writeInt(b.maxY);
            buf.writeInt(b.maxZ);
            ByteBufUtils.writeUTF8String(buf, b.planKey);
            ByteBufUtils.writeUTF8String(buf, b.name);
            ByteBufUtils.writeUTF8String(buf, b.tags);
            ByteBufUtils.writeUTF8String(buf, b.village);
            buf.writeBoolean(b.townHall);
            int np = Math.min(MAX_POINTS, b.points.size());
            buf.writeByte(np);
            for (int j = 0; j < np; j++) {
                int[] p = b.points.get(j);
                buf.writeByte(p[0]);
                buf.writeInt(p[1]);
                buf.writeInt(p[2]);
                buf.writeInt(p[3]);
            }
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        active = buf.readBoolean();
        if (!active) return;
        labelDistance = buf.readShort();
        int n = buf.readShort();
        buildings = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Building b = new Building();
            b.minX = buf.readInt();
            b.minY = buf.readInt();
            b.minZ = buf.readInt();
            b.maxX = buf.readInt();
            b.maxY = buf.readInt();
            b.maxZ = buf.readInt();
            b.planKey = ByteBufUtils.readUTF8String(buf);
            b.name = ByteBufUtils.readUTF8String(buf);
            b.tags = ByteBufUtils.readUTF8String(buf);
            b.village = ByteBufUtils.readUTF8String(buf);
            b.townHall = buf.readBoolean();
            int np = buf.readUnsignedByte();
            for (int j = 0; j < np; j++) {
                b.points.add(new int[]{buf.readByte(), buf.readInt(), buf.readInt(), buf.readInt()});
            }
            buildings.add(b);
        }
    }

    public static class Handler implements IMessageHandler<MsgVillageDebugView, IMessage> {
        @Override
        public IMessage onMessage(MsgVillageDebugView msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> ClientVillageDebugOverlay.accept(msg));
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgVillageDebugView, IMessage> {
        @Override
        public IMessage onMessage(MsgVillageDebugView msg, MessageContext ctx) {
            return null;
        }
    }
}
