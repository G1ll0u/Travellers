package com.jubitus.travellers.sound.net;

import static com.jubitus.travellers.net.TravellersNetwork.toClient;
import static com.jubitus.travellers.net.TravellersNetwork.toServer;

public final class SoundMessages {

    private SoundMessages() {
    }

    public static void register() {
        toClient(MsgPlayTravellerVoice.class, () -> new MsgPlayTravellerVoice.Handler());
        toClient(MsgStopTravellerVoice.class, () -> new MsgStopTravellerVoice.Handler());
    }
}
