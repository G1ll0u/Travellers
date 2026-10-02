package com.jubitus.travellers.choir.net;

import static com.jubitus.travellers.net.TravellersNetwork.toClient;
import static com.jubitus.travellers.net.TravellersNetwork.toServer;

public final class ChoirMessages {

    private ChoirMessages() {
    }

    public static void register() {
        toServer(MsgWorkerChoirToggle.class, MsgWorkerChoirToggle.Handler.class);
        toServer(MsgChoirSoundDone.class, MsgChoirSoundDone.Handler.class);
        toClient(MsgChoirSingerMute.class, MsgChoirSingerMute.Handler::new);
    }
}
