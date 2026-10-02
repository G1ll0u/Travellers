package com.jubitus.travellers.combat.net;

import static com.jubitus.travellers.net.TravellersNetwork.toClient;
import static com.jubitus.travellers.net.TravellersNetwork.toServer;

public final class CombatMessages {

    private CombatMessages() {
    }

    public static void register() {
        toClient(MsgSyncWorkerHostileList.class, () -> new MsgSyncWorkerHostileList.Handler());
        toServer(MsgSetWorkerHostile.class, MsgSetWorkerHostile.Handler.class);
        toServer(MsgRequestPlayerHostileList.class, MsgRequestPlayerHostileList.Handler.class);
    }
}
