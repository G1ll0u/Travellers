package com.jubitus.travellers.worldmap.net;

import com.jubitus.travellers.worldmap.gui.receiver.IPopulationMapReceiver;
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

public class MsgMapPopulationUpdate implements IMessage {

    public enum Kind { TRAVELLER, RAIDER, WORKER, STABLE }

    public static class Entry {
        public int dim;
        public BlockPos pos;
        public java.util.UUID uuid; // null for stables; lets the client glide the marker
        public Kind kind;
        public boolean virtual;
        public String label;
    }

    private List<Entry> entries = new ArrayList<>();

    public MsgMapPopulationUpdate() {}

    public MsgMapPopulationUpdate(List<Entry> entries) {
        this.entries = entries;
    }

    public List<Entry> getEntries() {
        return entries;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int n = buf.readInt();
        entries = new ArrayList<>(n);
        Kind[] kinds = Kind.values();
        for (int i = 0; i < n; i++) {
            Entry e = new Entry();
            e.dim = buf.readInt();
            e.pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
            e.kind = kinds[buf.readByte()];
            e.virtual = buf.readBoolean();
            e.label = ByteBufUtils.readUTF8String(buf);
            boolean hasUuid = buf.readBoolean();
            if (hasUuid) e.uuid = new java.util.UUID(buf.readLong(), buf.readLong());
            entries.add(e);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(entries != null ? entries.size() : 0);
        if (entries == null) return;
        for (Entry e : entries) {
            buf.writeInt(e.dim);
            buf.writeInt(e.pos.getX());
            buf.writeInt(e.pos.getY());
            buf.writeInt(e.pos.getZ());
            buf.writeByte(e.kind.ordinal());
            buf.writeBoolean(e.virtual);
            ByteBufUtils.writeUTF8String(buf, e.label != null ? e.label : "");
            buf.writeBoolean(e.uuid != null);
            if (e.uuid != null) {
                buf.writeLong(e.uuid.getMostSignificantBits());
                buf.writeLong(e.uuid.getLeastSignificantBits());
            }
        }
    }

    public static class ClientHandler implements IMessageHandler<MsgMapPopulationUpdate, IMessage> {
        @Override
        public IMessage onMessage(MsgMapPopulationUpdate msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                GuiScreen screen = Minecraft.getMinecraft().currentScreen;
                if (screen instanceof IPopulationMapReceiver) {
                    ((IPopulationMapReceiver) screen).receivePopulation(msg.getEntries());
                }
            });
            return null;
        }
    }
}
