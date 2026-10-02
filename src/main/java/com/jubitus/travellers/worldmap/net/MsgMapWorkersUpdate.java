package com.jubitus.travellers.worldmap.net;

import com.jubitus.travellers.worldmap.gui.receiver.IWorkerMapReceiver;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MsgMapWorkersUpdate implements IMessage {

    public static class Entry {
        public int entityId;
        public int dim;
        public BlockPos pos;
        public String name;
        public boolean isLeader;
        public int ownerColor; // RGB (no alpha)
        public UUID uuid;     // null for legacy entries
        public float health = -1f;    // -1 = unknown
        public float maxHealth = -1f;
        public boolean inBattle;
        // Current activity as an i18n key ("" = idle/none), and who the worker follows: the leader's
        // name, or empty + followsOwner=true when it follows its owner directly.
        public String statusKey = "";
        public String followName = "";
        public boolean followsOwner;
        // Duty kind of an on-duty worker (e.g. STABLE_HAND), "" for none: picks its map marker.
        public String role = "";
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
            boolean hasUuid = buf.readBoolean();
            if (hasUuid) e.uuid = new UUID(buf.readLong(), buf.readLong());
            e.health = buf.readFloat();
            e.maxHealth = buf.readFloat();
            e.inBattle = buf.readBoolean();
            e.statusKey = ByteBufUtils.readUTF8String(buf);
            e.followName = ByteBufUtils.readUTF8String(buf);
            e.followsOwner = buf.readBoolean();
            e.role = ByteBufUtils.readUTF8String(buf);
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
            buf.writeBoolean(e.uuid != null);
            if (e.uuid != null) { buf.writeLong(e.uuid.getMostSignificantBits()); buf.writeLong(e.uuid.getLeastSignificantBits()); }
            buf.writeFloat(e.health);
            buf.writeFloat(e.maxHealth);
            buf.writeBoolean(e.inBattle);
            ByteBufUtils.writeUTF8String(buf, e.statusKey != null ? e.statusKey : "");
            ByteBufUtils.writeUTF8String(buf, e.followName != null ? e.followName : "");
            buf.writeBoolean(e.followsOwner);
            ByteBufUtils.writeUTF8String(buf, e.role != null ? e.role : "");
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
