package com.jubitus.travellers.notify.net;

import com.jubitus.travellers.notify.client.ClientJournalCache;
import com.jubitus.travellers.notify.JournalEntry;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;

/** Server -> client: full (capped) notification history, response to MsgRequestJournalHistory. */
public class MsgJournalHistoryData implements IMessage {

    private NBTTagCompound wrapper; // contains NBTTagList "E"

    public MsgJournalHistoryData() {}

    public MsgJournalHistoryData(List<JournalEntry> entries) {
        NBTTagList list = new NBTTagList();
        for (JournalEntry e : entries) {
            list.appendTag(e.toNbt());
        }
        wrapper = new NBTTagCompound();
        wrapper.setTag("E", list);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        wrapper = ByteBufUtils.readTag(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeTag(buf, wrapper != null ? wrapper : new NBTTagCompound());
    }

    public static class ClientHandler implements IMessageHandler<MsgJournalHistoryData, IMessage> {
        @Override
        public IMessage onMessage(MsgJournalHistoryData msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                List<JournalEntry> entries = new ArrayList<>();
                if (msg.wrapper != null) {
                    NBTTagList list = msg.wrapper.getTagList("E", 10);
                    for (int i = 0; i < list.tagCount(); i++) {
                        entries.add(JournalEntry.fromNbt(list.getCompoundTagAt(i)));
                    }
                }
                ClientJournalCache.setAll(entries);
            });
            return null;
        }
    }
}
