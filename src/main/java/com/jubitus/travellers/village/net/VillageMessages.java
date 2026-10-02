package com.jubitus.travellers.village.net;

import static com.jubitus.travellers.net.TravellersNetwork.toClient;
import static com.jubitus.travellers.net.TravellersNetwork.toServer;

public final class VillageMessages {

    private VillageMessages() {
    }

    public static void register() {
        toServer(MsgWorkerRequestVillageRelations.class, MsgWorkerRequestVillageRelations.Handler.class);
        toClient(MsgWorkerVillageRelationsList.class, MsgWorkerVillageRelationsList.Handler::new);
        toServer(MsgSetVillageEnemy.class, MsgSetVillageEnemy.Handler.class);
    }
}
