package com.jubitus.travellers.worldmap.net;

import com.jubitus.travellers.worldmap.gui.receiver.IBattleMapReceiver;
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

/** One entry per active virtual battle; the tooltip label is composed server-side. */
public class MsgMapBattleUpdate implements IMessage {

    public static class Entry {
        public int dim;
        public BlockPos pos;
        public int aliveA;
        public int aliveB;
        public boolean campAssault;
        public String label;
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
        for (int i = 0; i < n; i++) {
            Entry e = new Entry();
            e.dim = buf.readInt();
            e.pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
            e.aliveA = buf.readInt();
            e.aliveB = buf.readInt();
            e.campAssault = buf.readBoolean();
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
            buf.writeInt(e.aliveA);
            buf.writeInt(e.aliveB);
            buf.writeBoolean(e.campAssault);
            ByteBufUtils.writeUTF8String(buf, e.label != null ? e.label : "");
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

    public static class NoopHandler implements IMessageHandler<MsgMapBattleUpdate, IMessage> {
        @Override public IMessage onMessage(MsgMapBattleUpdate msg, MessageContext ctx) { return null; }
    }
}
