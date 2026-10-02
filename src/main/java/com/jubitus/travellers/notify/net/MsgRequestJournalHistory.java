package com.jubitus.travellers.notify.net;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.notify.JournalEntry;
import com.jubitus.travellers.notify.NotificationJournalData;
import com.jubitus.travellers.net.TravellerFollowNet;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.List;

/** Client -> server: request the full notification history, sent whenever the formation hub's
 * history panel is (re)opened, so the panel always starts from the authoritative server state. */
public class MsgRequestJournalHistory implements IMessage {

    public MsgRequestJournalHistory() {}

    @Override
    public void fromBytes(ByteBuf buf) {}

    @Override
    public void toBytes(ByteBuf buf) {}

    public static class Handler implements IMessageHandler<MsgRequestJournalHistory, IMessage> {
        @Override
        public IMessage onMessage(MsgRequestJournalHistory msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                List<JournalEntry> all = NotificationJournalData.get(player.world).getFor(player.getUniqueID());

                int cap = TravellersConfig.NOTIFICATIONS.historyPanelMaxEntries;
                List<JournalEntry> capped = all.size() > cap ? all.subList(all.size() - cap, all.size()) : all;

                TravellerFollowNet.CHANNEL.sendTo(new MsgJournalHistoryData(capped), player);
            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgRequestJournalHistory, IMessage> {
        @Override
        public IMessage onMessage(MsgRequestJournalHistory msg, MessageContext ctx) { return null; }
    }
}
