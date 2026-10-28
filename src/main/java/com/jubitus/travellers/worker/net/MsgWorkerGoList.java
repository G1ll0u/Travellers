package com.jubitus.travellers.worker.net;

import com.jubitus.travellers.worldmap.gui.receiver.IWorkerGoListReceiver;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;

public class MsgWorkerGoList implements IMessage {

    public enum Kind { VILLAGE, HOME, CONTROLLED_VILLAGE, STABLE }

    public static class Entry {
        public Kind kind;
        public BlockPos pos;
        public String label;
        public int distance;
        /** Barrack / Stable only: occupancy for the map tooltip. */
        @javax.annotation.Nullable public com.jubitus.travellers.follow.net.FormationZoneInfo zone;

        public static Entry village(BlockPos pos, String label, int distance) {
            Entry e = new Entry();
            e.kind = Kind.VILLAGE;
            e.pos = pos;
            e.label = label;
            e.distance = distance;
            return e;
        }

        public static Entry home(BlockPos pos, String label, int distance) {
            Entry e = new Entry();
            e.kind = Kind.HOME;
            e.pos = pos;
            e.label = label;
            e.distance = distance;
            return e;
        }

        public static Entry controlledVillage(BlockPos pos, String label, int distance) {
            Entry e = new Entry();
            e.kind = Kind.CONTROLLED_VILLAGE;
            e.pos = pos;
            e.label = label;
            e.distance = distance;
            return e;
        }

        public static Entry stable(BlockPos pos, String label, int distance) {
            Entry e = new Entry();
            e.kind = Kind.STABLE;
            e.pos = pos;
            e.label = label;
            e.distance = distance;
            return e;
        }
    }

    private int workerId;
    private List<Entry> entries;

    public MsgWorkerGoList() {}
    public MsgWorkerGoList(int workerId, List<Entry> entries) {
        this.workerId = workerId;
        this.entries = entries;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        workerId = buf.readInt();
        int n = buf.readInt();
        entries = new ArrayList<>(n);

        for (int i = 0; i < n; i++) {
            int kind = buf.readUnsignedByte();
            int x = buf.readInt();
            int y = buf.readInt();
            int z = buf.readInt();
            String label = ByteBufUtils.readUTF8String(buf);
            int dist = buf.readInt();

            Entry e = new Entry();
            e.kind = (kind == 0) ? Kind.VILLAGE : (kind == 2) ? Kind.CONTROLLED_VILLAGE
                    : (kind == 3) ? Kind.STABLE : Kind.HOME;
            e.pos = new BlockPos(x, y, z);
            e.label = label;
            e.distance = dist;
            if (buf.readBoolean()) e.zone = com.jubitus.travellers.follow.net.FormationZoneInfo.fromNbt(ByteBufUtils.readTag(buf));
            entries.add(e);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(workerId);
        int n = (entries != null) ? entries.size() : 0;
        buf.writeInt(n);

        for (int i = 0; i < n; i++) {
            Entry e = entries.get(i);
            buf.writeByte((byte) (e.kind == Kind.VILLAGE ? 0 : e.kind == Kind.CONTROLLED_VILLAGE ? 2
                    : e.kind == Kind.STABLE ? 3 : 1));
            buf.writeInt(e.pos.getX());
            buf.writeInt(e.pos.getY());
            buf.writeInt(e.pos.getZ());
            ByteBufUtils.writeUTF8String(buf, e.label);
            buf.writeInt(e.distance);
            buf.writeBoolean(e.zone != null);
            if (e.zone != null) ByteBufUtils.writeTag(buf, e.zone.toNbt());
        }
    }

    // ---------- CLIENT HANDLER ----------
    public static class ClientHandler implements IMessageHandler<MsgWorkerGoList, IMessage> {
        @Override
        public IMessage onMessage(MsgWorkerGoList msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                GuiScreen screen = Minecraft.getMinecraft().currentScreen;
                if (screen instanceof IWorkerGoListReceiver) {
                    ((IWorkerGoListReceiver) screen).receiveGoList(msg.entries);
                }
            });
            return null;
        }
    }
}
