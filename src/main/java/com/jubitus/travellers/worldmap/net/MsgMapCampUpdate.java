package com.jubitus.travellers.worldmap.net;

import com.jubitus.travellers.worldmap.gui.receiver.ICampMapReceiver;
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

/**
 * One entry per drawable camp object (firepit or shelter slot) or rest spot. Kept flat (rather than one entry
 * per campsite with nested slot lists) to reuse the same simple marker/tooltip pipeline as
 * {@link MsgMapPopulationUpdate}. The tooltip text is fully composed server-side, same precedent
 * as the existing plain-text "/travellersdebug camp" command output — no i18n needed for debug data.
 */
public class MsgMapCampUpdate implements IMessage {

    public enum ObjKind { FIREPIT, SLOT, REST_SPOT }

    public enum SlotState { EMPTY, DISABLED, BUILDING, BUILT, TEARING_DOWN }

    public static class Entry {
        public int dim;
        public BlockPos pos;
        public ObjKind kind;

        // Firepit fields
        public boolean firepitBuilt;
        public boolean firepitLit;

        // Slot fields
        public SlotState slotState = SlotState.EMPTY;
        /** True if the occupying traveller/raider currently has no loaded Entity (virtualized). */
        public boolean occupantVirtual;

        public String label;
    }

    private List<Entry> entries = new ArrayList<>();

    public MsgMapCampUpdate() {}

    public MsgMapCampUpdate(List<Entry> entries) {
        this.entries = entries;
    }

    public List<Entry> getEntries() {
        return entries;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int n = buf.readInt();
        entries = new ArrayList<>(n);
        ObjKind[] kinds = ObjKind.values();
        SlotState[] states = SlotState.values();
        for (int i = 0; i < n; i++) {
            Entry e = new Entry();
            e.dim = buf.readInt();
            e.pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
            e.kind = kinds[buf.readByte()];
            e.firepitBuilt = buf.readBoolean();
            e.firepitLit = buf.readBoolean();
            e.slotState = states[buf.readByte()];
            e.occupantVirtual = buf.readBoolean();
            e.label = ByteBufUtils.readUTF8String(buf);
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
            buf.writeBoolean(e.firepitBuilt);
            buf.writeBoolean(e.firepitLit);
            buf.writeByte(e.slotState.ordinal());
            buf.writeBoolean(e.occupantVirtual);
            ByteBufUtils.writeUTF8String(buf, e.label != null ? e.label : "");
        }
    }

    public static class ClientHandler implements IMessageHandler<MsgMapCampUpdate, IMessage> {
        @Override
        public IMessage onMessage(MsgMapCampUpdate msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                GuiScreen screen = Minecraft.getMinecraft().currentScreen;
                if (screen instanceof ICampMapReceiver) {
                    ((ICampMapReceiver) screen).receiveCamps(msg.getEntries());
                }
            });
            return null;
        }
    }
}
