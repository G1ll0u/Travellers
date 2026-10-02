package com.jubitus.travellers.notify.net;

import static com.jubitus.travellers.net.TravellersNetwork.toClient;
import static com.jubitus.travellers.net.TravellersNetwork.toServer;

public final class NotifyMessages {

    private NotifyMessages() {
    }

    public static void register() {
        toClient(MsgShowNotification.class, () -> new MsgShowNotification.Handler());
        toServer(MsgRequestJournalHistory.class, MsgRequestJournalHistory.Handler.class);
        toClient(MsgJournalHistoryData.class, () -> new MsgJournalHistoryData.ClientHandler());
        toClient(MsgJournalEntryAdded.class, () -> new MsgJournalEntryAdded.ClientHandler());
    }
}
