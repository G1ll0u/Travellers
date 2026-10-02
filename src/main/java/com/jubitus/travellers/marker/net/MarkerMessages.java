package com.jubitus.travellers.marker.net;

import static com.jubitus.travellers.net.TravellersNetwork.toClient;
import static com.jubitus.travellers.net.TravellersNetwork.toServer;

public final class MarkerMessages {

    private MarkerMessages() {
    }

    public static void register() {
        toServer(MsgSaveWorkerOwnerVisuals.class, MsgSaveWorkerOwnerVisuals.Handler.class);
        toClient(MsgOwnerVisualsSync.class, MsgOwnerVisualsSync.Handler::new);
    }
}
