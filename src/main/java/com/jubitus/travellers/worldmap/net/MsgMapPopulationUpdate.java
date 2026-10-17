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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The world population for one map viewer, in one dimension. A unit's fixed facts (kind, uuid,
 * label) travel once as a {@link Def} under a per-viewer id; every packet then only carries each
 * visible unit's id, position and virtual flag. The client keeps the id table, so screens still
 * receive complete {@link Entry} lists.
 */
public class MsgMapPopulationUpdate implements IMessage {

    /** RAID_RAIDER is a raider of a running raid, drawn in its own colour; it counts as a raider everywhere else. */
    public enum Kind { TRAVELLER, RAIDER, WORKER, STABLE, RAID_RAIDER }

    public static class Entry {
        public int dim;
        public BlockPos pos;
        public UUID uuid; // null for stables; lets the client glide the marker
        public Kind kind;
        public boolean virtual;
        public String label;
        /** Server tick the position was measured at (client side, copied from the message). */
        public long sampleTick;
    }

    /** A unit's fixed facts, sent when the viewer first sees it or when its label changes. */
    public static final class Def {
        public final int id;
        public final Kind kind;
        public final UUID uuid;
        public final String label;

        public Def(int id, Kind kind, UUID uuid, String label) {
            this.id = id;
            this.kind = kind;
            this.uuid = uuid;
            this.label = label;
        }
    }

    /** What changes every second for a visible unit. */
    public static final class Dyn {
        public final int id;
        public final BlockPos pos;
        public final boolean virtual;

        public Dyn(int id, BlockPos pos, boolean virtual) {
            this.id = id;
            this.pos = pos;
            this.virtual = virtual;
        }
    }

    private int dim;
    /** True on the first packet after a map is opened: the client forgets its id table first. */
    private boolean full;
    private List<Def> defs = new ArrayList<>();
    private List<Dyn> dyns = new ArrayList<>();
    /** Entries without a uuid (stables), sent whole every time. */
    private List<Entry> extras = new ArrayList<>();
    private long serverTick;

    public MsgMapPopulationUpdate() {}

    public MsgMapPopulationUpdate(int dim, boolean full, List<Def> defs, List<Dyn> dyns, List<Entry> extras) {
        this.dim = dim;
        this.full = full;
        this.defs = defs;
        this.dyns = dyns;
        this.extras = extras;
    }

    /** Server tick the positions were measured at, so the client spaces them by real game time. */
    public MsgMapPopulationUpdate withServerTick(long tick) {
        this.serverTick = tick;
        return this;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dim = buf.readInt();
        full = buf.readBoolean();
        serverTick = buf.readLong();
        Kind[] kinds = Kind.values();
        int nDefs = ByteBufUtils.readVarInt(buf, 5);
        defs = new ArrayList<>(nDefs);
        for (int i = 0; i < nDefs; i++) {
            int id = ByteBufUtils.readVarInt(buf, 5);
            Kind kind = kinds[buf.readByte()];
            UUID uuid = new UUID(buf.readLong(), buf.readLong());
            defs.add(new Def(id, kind, uuid, ByteBufUtils.readUTF8String(buf)));
        }
        int nDyns = ByteBufUtils.readVarInt(buf, 5);
        dyns = new ArrayList<>(nDyns);
        for (int i = 0; i < nDyns; i++) {
            int id = ByteBufUtils.readVarInt(buf, 5);
            int x = buf.readInt();
            int y = buf.readShort();
            int z = buf.readInt();
            dyns.add(new Dyn(id, new BlockPos(x, y, z), buf.readBoolean()));
        }
        int nExtras = ByteBufUtils.readVarInt(buf, 5);
        extras = new ArrayList<>(nExtras);
        for (int i = 0; i < nExtras; i++) {
            Entry e = new Entry();
            e.dim = dim;
            e.pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
            e.kind = kinds[buf.readByte()];
            e.virtual = buf.readBoolean();
            e.label = ByteBufUtils.readUTF8String(buf);
            extras.add(e);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dim);
        buf.writeBoolean(full);
        buf.writeLong(serverTick);
        ByteBufUtils.writeVarInt(buf, defs.size(), 5);
        for (Def d : defs) {
            ByteBufUtils.writeVarInt(buf, d.id, 5);
            buf.writeByte(d.kind.ordinal());
            buf.writeLong(d.uuid.getMostSignificantBits());
            buf.writeLong(d.uuid.getLeastSignificantBits());
            ByteBufUtils.writeUTF8String(buf, d.label != null ? d.label : "");
        }
        ByteBufUtils.writeVarInt(buf, dyns.size(), 5);
        for (Dyn d : dyns) {
            ByteBufUtils.writeVarInt(buf, d.id, 5);
            buf.writeInt(d.pos.getX());
            buf.writeShort(d.pos.getY());
            buf.writeInt(d.pos.getZ());
            buf.writeBoolean(d.virtual);
        }
        ByteBufUtils.writeVarInt(buf, extras.size(), 5);
        for (Entry e : extras) {
            buf.writeInt(e.pos.getX());
            buf.writeInt(e.pos.getY());
            buf.writeInt(e.pos.getZ());
            buf.writeByte(e.kind.ordinal());
            buf.writeBoolean(e.virtual);
            ByteBufUtils.writeUTF8String(buf, e.label != null ? e.label : "");
        }
    }

    public static class ClientHandler implements IMessageHandler<MsgMapPopulationUpdate, IMessage> {
        /** Id table of the packets received so far; only touched on the client main thread. */
        private static final Map<Integer, Def> TABLE = new HashMap<>();

        @Override
        public IMessage onMessage(MsgMapPopulationUpdate msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                List<Entry> entries = resolve(msg);
                GuiScreen screen = Minecraft.getMinecraft().currentScreen;
                if (screen instanceof IPopulationMapReceiver) {
                    ((IPopulationMapReceiver) screen).receivePopulation(entries);
                }
            });
            return null;
        }

        // The table is kept up to date even when no map screen is open, so it never drifts from the server's.
        public static List<Entry> resolve(MsgMapPopulationUpdate msg) {
            if (msg.full) TABLE.clear();
            for (Def d : msg.defs) TABLE.put(d.id, d);
            List<Entry> out = new ArrayList<>(msg.dyns.size() + msg.extras.size());
            Set<Integer> present = new HashSet<>();
            for (Dyn dyn : msg.dyns) {
                Def d = TABLE.get(dyn.id);
                if (d == null) continue;
                present.add(dyn.id);
                Entry e = new Entry();
                e.dim = msg.dim;
                e.pos = dyn.pos;
                e.uuid = d.uuid;
                e.kind = d.kind;
                e.virtual = dyn.virtual;
                e.label = d.label;
                e.sampleTick = msg.serverTick;
                out.add(e);
            }
            TABLE.keySet().retainAll(present);
            for (Entry e : msg.extras) e.sampleTick = msg.serverTick;
            out.addAll(msg.extras);
            return out;
        }
    }
}
