package com.jubitus.travellers.worldmap.net;

import com.jubitus.travellers.worldmap.gui.receiver.IStrikeMapReceiver;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The melee hits and arrows of the last second in one dimension, for the map combat animation. */
public class MsgMapBattleStrikes implements IMessage {

    public static class Entry {
        public byte kind; // MapStrikeLog.MELEE / ARROW
        public byte ageTicks; // how long before the send the strike fell
        public UUID attacker;
        public int attackerX, attackerZ;
        public int victimX, victimZ;
        /** Server tick the strike fell at (client side, from the message tick and the age). */
        public long tick;
    }

    private int dim;
    private long serverTick;
    private List<Entry> entries = new ArrayList<>();

    public MsgMapBattleStrikes() {}

    public MsgMapBattleStrikes(int dim, long serverTick, List<Entry> entries) {
        this.dim = dim;
        this.serverTick = serverTick;
        this.entries = entries;
    }

    public int getDim() {
        return dim;
    }

    public List<Entry> getEntries() {
        return entries;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dim = buf.readInt();
        serverTick = buf.readLong();
        int n = buf.readUnsignedShort();
        entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Entry e = new Entry();
            e.kind = buf.readByte();
            e.ageTicks = buf.readByte();
            e.tick = serverTick - e.ageTicks;
            e.attacker = new UUID(buf.readLong(), buf.readLong());
            e.attackerX = buf.readInt();
            e.attackerZ = buf.readInt();
            e.victimX = buf.readInt();
            e.victimZ = buf.readInt();
            entries.add(e);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dim);
        buf.writeLong(serverTick);
        buf.writeShort(entries.size());
        for (Entry e : entries) {
            buf.writeByte(e.kind);
            buf.writeByte(e.ageTicks);
            buf.writeLong(e.attacker.getMostSignificantBits());
            buf.writeLong(e.attacker.getLeastSignificantBits());
            buf.writeInt(e.attackerX);
            buf.writeInt(e.attackerZ);
            buf.writeInt(e.victimX);
            buf.writeInt(e.victimZ);
        }
    }

    public static class ClientHandler implements IMessageHandler<MsgMapBattleStrikes, IMessage> {
        @Override
        public IMessage onMessage(MsgMapBattleStrikes msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                GuiScreen screen = Minecraft.getMinecraft().currentScreen;
                if (screen instanceof IStrikeMapReceiver) {
                    ((IStrikeMapReceiver) screen).receiveStrikes(msg.getDim(), msg.getEntries());
                }
            });
            return null;
        }
    }
}
