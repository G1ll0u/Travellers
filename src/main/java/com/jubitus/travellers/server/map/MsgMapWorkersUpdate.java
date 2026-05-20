package com.jubitus.travellers.server.map;

import com.jubitus.travellers.client.gui.worldmap.IWorkerMapReceiver;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.*;

import java.util.ArrayList;
import java.util.List;

public class MsgMapWorkersUpdate implements IMessage {

    public static class Entry {
        public int entityId;
        public int dim;
        public BlockPos pos;
        public String name;
        public boolean isLeader;
        public int ownerColor; // RGB (no alpha)
    }

    private List<Entry> entries = new ArrayList<>();

    public MsgMapWorkersUpdate() {}

    public MsgMapWorkersUpdate(List<Entry> entries) {
        this.entries = entries;
    }

    public List<Entry> getEntries() {
        return entries;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int n = buf.readInt();
        entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Entry e = new Entry();
            e.entityId = buf.readInt();
            e.dim = buf.readInt();
            int x = buf.readInt();
            int y = buf.readInt();
            int z = buf.readInt();
            e.pos = new BlockPos(x, y, z);
            e.name = ByteBufUtils.readUTF8String(buf);
            e.isLeader = buf.readBoolean();
            e.ownerColor = buf.readInt();
            entries.add(e);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(entries != null ? entries.size() : 0);
        if (entries == null) return;
        for (Entry e : entries) {
            buf.writeInt(e.entityId);
            buf.writeInt(e.dim);
            buf.writeInt(e.pos.getX());
            buf.writeInt(e.pos.getY());
            buf.writeInt(e.pos.getZ());
            ByteBufUtils.writeUTF8String(buf, e.name != null ? e.name : "");
            buf.writeBoolean(e.isLeader);
            buf.writeInt(e.ownerColor);
        }
    }

    public static class ClientHandler implements IMessageHandler<MsgMapWorkersUpdate, IMessage> {
        @Override
        public IMessage onMessage(MsgMapWorkersUpdate msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                GuiScreen screen = Minecraft.getMinecraft().currentScreen;
                if (screen instanceof IWorkerMapReceiver) {
                    ((IWorkerMapReceiver) screen).receiveWorkers(msg.getEntries());
                }
            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgMapWorkersUpdate, IMessage> {
        @Override public IMessage onMessage(MsgMapWorkersUpdate msg, MessageContext ctx) { return null; }
    }
}
