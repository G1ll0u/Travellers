package com.jubitus.travellers.worldmap.net;

import com.jubitus.travellers.worldmap.gui.receiver.IBattleMapReceiver;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;

/** One entry per battle the viewer can see, running or just ended, seen from the viewer's side; the client writes the text. */
public class MsgMapBattleUpdate implements IMessage {

    /** Who fights on a side, as {@link Side#kinds} bits. */
    public static final int KIND_YOURS = 1, KIND_HIRED = 2, KIND_TRAVELLERS = 4, KIND_RAIDERS = 8;

    public enum Result { RUNNING, FIRST_WON, SECOND_WON, NO_WINNER }

    public static class Side {
        public int standing;
        public int fallen;
        public int kinds;

        void write(ByteBuf buf) {
            buf.writeInt(standing);
            buf.writeInt(fallen);
            buf.writeByte(kinds);
        }

        static Side read(ByteBuf buf) {
            Side s = new Side();
            s.standing = buf.readInt();
            s.fallen = buf.readInt();
            s.kinds = buf.readByte();
            return s;
        }
    }

    public static class Entry {
        public int dim;
        public BlockPos pos;
        public boolean campAssault;
        /** The viewer has units in this battle; they are always on {@link #first}. */
        public boolean involved;
        public Side first = new Side();
        public Side second = new Side();
        public Result result = Result.RUNNING;
        /** Seconds since the battle started (running) or since it ended. */
        public int seconds;
    }

    private List<Entry> entries = new ArrayList<>();

    public MsgMapBattleUpdate() {}

    public MsgMapBattleUpdate(List<Entry> entries) {
        this.entries = entries;
    }

    public List<Entry> getEntries() {
        return entries;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int n = buf.readInt();
        entries = new ArrayList<>(n);
        Result[] results = Result.values();
        for (int i = 0; i < n; i++) {
            Entry e = new Entry();
            e.dim = buf.readInt();
            e.pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
            e.campAssault = buf.readBoolean();
            e.involved = buf.readBoolean();
            e.first = Side.read(buf);
            e.second = Side.read(buf);
            int r = buf.readByte();
            e.result = r >= 0 && r < results.length ? results[r] : Result.RUNNING;
            e.seconds = buf.readInt();
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
            buf.writeBoolean(e.campAssault);
            buf.writeBoolean(e.involved);
            e.first.write(buf);
            e.second.write(buf);
            buf.writeByte(e.result.ordinal());
            buf.writeInt(e.seconds);
        }
    }

    public static class ClientHandler implements IMessageHandler<MsgMapBattleUpdate, IMessage> {
        @Override
        public IMessage onMessage(MsgMapBattleUpdate msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                GuiScreen screen = Minecraft.getMinecraft().currentScreen;
                if (screen instanceof IBattleMapReceiver) {
                    ((IBattleMapReceiver) screen).receiveBattles(msg.getEntries());
                }
            });
            return null;
        }
    }
}
