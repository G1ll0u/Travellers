package com.jubitus.travellers.worldmap.net;

import com.jubitus.travellers.worldmap.gui.receiver.IForeignUnitMapReceiver;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.*;

import java.util.ArrayList;
import java.util.List;

/** Foreign (not the receiving player's own) travellers/raiders/workers discovered within vision range. */
public class MsgMapForeignUnitsUpdate implements IMessage {

    public static class Entry {
        public int dim;
        public BlockPos pos;
        public java.util.UUID uuid; // lets the client glide the marker between updates
        public String name;
        public String entityClass; // "TRAVELLER" | "RAIDER" | "WORKER"
        public boolean hired;
        public int ownerColor;     // only meaningful when hired
        public boolean virtual;
        public float health = -1f;    // -1 = hidden
        public float maxHealth = -1f;
        public boolean inBattle;
        /** Server tick the position was measured at (client side, copied from the message). */
        public long sampleTick;
    }

    private List<Entry> entries = new ArrayList<>();
    private long serverTick;

    public MsgMapForeignUnitsUpdate() {}

    public MsgMapForeignUnitsUpdate(List<Entry> entries) {
        this.entries = entries;
    }

    public List<Entry> getEntries() {
        return entries;
    }

    /** Server tick the positions were measured at, so the client spaces them by real game time. */
    public MsgMapForeignUnitsUpdate withServerTick(long tick) {
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
            e.dim = buf.readInt();
            int x = buf.readInt();
            int y = buf.readInt();
            int z = buf.readInt();
            e.pos = new BlockPos(x, y, z);
            e.name = ByteBufUtils.readUTF8String(buf);
            e.entityClass = ByteBufUtils.readUTF8String(buf);
            e.hired = buf.readBoolean();
            e.ownerColor = buf.readInt();
            e.virtual = buf.readBoolean();
            e.health = buf.readFloat();
            e.maxHealth = buf.readFloat();
            e.inBattle = buf.readBoolean();
            boolean hasUuid = buf.readBoolean();
            if (hasUuid) e.uuid = new java.util.UUID(buf.readLong(), buf.readLong());
            entries.add(e);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(serverTick);
        buf.writeInt(entries != null ? entries.size() : 0);
        if (entries == null) return;
        for (Entry e : entries) {
            buf.writeInt(e.dim);
            buf.writeInt(e.pos.getX());
            buf.writeInt(e.pos.getY());
            buf.writeInt(e.pos.getZ());
            ByteBufUtils.writeUTF8String(buf, e.name != null ? e.name : "");
            ByteBufUtils.writeUTF8String(buf, e.entityClass != null ? e.entityClass : "TRAVELLER");
            buf.writeBoolean(e.hired);
            buf.writeInt(e.ownerColor);
            buf.writeBoolean(e.virtual);
            buf.writeFloat(e.health);
            buf.writeFloat(e.maxHealth);
            buf.writeBoolean(e.inBattle);
            buf.writeBoolean(e.uuid != null);
            if (e.uuid != null) {
                buf.writeLong(e.uuid.getMostSignificantBits());
                buf.writeLong(e.uuid.getLeastSignificantBits());
            }
        }
    }

    public static class ClientHandler implements IMessageHandler<MsgMapForeignUnitsUpdate, IMessage> {
        @Override
        public IMessage onMessage(MsgMapForeignUnitsUpdate msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                GuiScreen screen = Minecraft.getMinecraft().currentScreen;
                if (screen instanceof IForeignUnitMapReceiver) {
                    ((IForeignUnitMapReceiver) screen).receiveForeignUnits(msg.getEntries());
                }
            });
            return null;
        }
    }
}
