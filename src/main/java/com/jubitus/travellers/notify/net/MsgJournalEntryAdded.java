package com.jubitus.travellers.notify.net;

import com.jubitus.travellers.notify.client.ClientJournalCache;
import com.jubitus.travellers.notify.JournalEntry;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** Server -> client: one freshly journaled Event notification, pushed right after
 * NotificationJournalData.append so the history panel stays live while the hub is open. */
public class MsgJournalEntryAdded implements IMessage {

    private NBTTagCompound tag;

    public MsgJournalEntryAdded() {}

    public MsgJournalEntryAdded(JournalEntry entry) {
        this.tag = entry.toNbt();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        tag = ByteBufUtils.readTag(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeTag(buf, tag != null ? tag : new NBTTagCompound());
    }

    public static class ClientHandler implements IMessageHandler<MsgJournalEntryAdded, IMessage> {
        @Override
        public IMessage onMessage(MsgJournalEntryAdded msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                if (msg.tag != null) {
                    ClientJournalCache.append(JournalEntry.fromNbt(msg.tag));
                }
            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgJournalEntryAdded, IMessage> {
        @Override
        public IMessage onMessage(MsgJournalEntryAdded msg, MessageContext ctx) { return null; }
    }
}
