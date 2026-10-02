package com.jubitus.travellers.zone.net;

import static com.jubitus.travellers.net.TravellersNetwork.toClient;
import static com.jubitus.travellers.net.TravellersNetwork.toServer;

public final class ZoneMessages {

    private ZoneMessages() {
    }

    public static void register() {
        toServer(MsgWorkerGoHome.class, MsgWorkerGoHome.Handler.class);
        toClient(MsgSyncWorkerHome.class, MsgSyncWorkerHome.ClientHandler::new);
        toClient(MsgOpenZonePrompt.class, MsgOpenZonePrompt.ClientHandler::new);
        toServer(MsgCreatePlayerZone.class, MsgCreatePlayerZone.Handler.class);
        toClient(MsgZoneActionResult.class, MsgZoneActionResult.ClientHandler::new);
        toServer(MsgDeletePlayerZone.class, MsgDeletePlayerZone.Handler.class);
        toServer(MsgRequestWorkerBarracksData.class, MsgRequestWorkerBarracksData.Handler.class);
        toServer(MsgAssignWorkerBarrack.class, MsgAssignWorkerBarrack.Handler.class);
        toServer(MsgUnassignWorkerBarrack.class, MsgUnassignWorkerBarrack.Handler.class);
        toClient(MsgWorkerBarracksData.class, MsgWorkerBarracksData.ClientHandler::new);
        toServer(MsgRequestWorkerZoneData.class, MsgRequestWorkerZoneData.Handler.class);
        toServer(MsgAssignWorkerDuty.class, MsgAssignWorkerDuty.Handler.class);
        toServer(MsgUnassignWorkerDuty.class, MsgUnassignWorkerDuty.Handler.class);
        toClient(MsgWorkerZoneData.class, MsgWorkerZoneData.ClientHandler::new);
    }
}
