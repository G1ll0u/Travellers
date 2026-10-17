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
        // The worker group (leader) this worker escorts, or null when it follows its owner or nobody.
        public UUID followLeaderUuid;
        public boolean followsOwner;
        // Duty kind of an on-duty worker (e.g. STABLE_HAND), "" for none: picks its map marker.
        public String role = "";
        /** Centre of the zone an on-duty worker serves, else null. */
        public BlockPos dutyZone;
        /** Server tick the position was measured at (client side, copied from the message). */
        public long sampleTick;
    }

    private List<Entry> entries = new ArrayList<>();
    private long serverTick;

    public MsgMapWorkersUpdate() {}

    public MsgMapWorkersUpdate(List<Entry> entries) {
        this.entries = entries;
    }

    public List<Entry> getEntries() {
        return entries;
    }

    /** Server tick the positions were measured at, so the client spaces them by real game time. */
    public MsgMapWorkersUpdate withServerTick(long tick) {
        this.serverTick = tick;
        return this;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        serverTick = buf.readLong();
        int n = buf.readInt();
        entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Entry e = new Entry();
            e.sampleTick = serverTick;
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
            if (buf.readBoolean()) e.followLeaderUuid = new UUID(buf.readLong(), buf.readLong());
            if (buf.readBoolean()) e.dutyZone = BlockPos.fromLong(buf.readLong());
            entries.add(e);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(serverTick);
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
            buf.writeBoolean(e.followLeaderUuid != null);
            if (e.followLeaderUuid != null) {
                buf.writeLong(e.followLeaderUuid.getMostSignificantBits());
                buf.writeLong(e.followLeaderUuid.getLeastSignificantBits());
            }
            buf.writeBoolean(e.dutyZone != null);
            if (e.dutyZone != null) buf.writeLong(e.dutyZone.toLong());
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
}
