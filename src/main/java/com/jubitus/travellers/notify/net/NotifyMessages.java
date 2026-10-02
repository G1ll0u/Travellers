package com.jubitus.travellers.notify.net;

import static com.jubitus.travellers.net.TravellersNetwork.toClient;
import static com.jubitus.travellers.net.TravellersNetwork.toServer;

public final class NotifyMessages {

    private NotifyMessages() {
    }

    public static void register() {
        toClient(MsgShowNotification.class, MsgShowNotification.Handler::new);
        toServer(MsgRequestJournalHistory.class, MsgRequestJournalHistory.Handler.class);
        toClient(MsgJournalHistoryData.class, MsgJournalHistoryData.ClientHandler::new);
        toClient(MsgJournalEntryAdded.class, MsgJournalEntryAdded.ClientHandler::new);
    }
}
