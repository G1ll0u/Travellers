package com.jubitus.travellers.unit.equipment.net;

import static com.jubitus.travellers.net.TravellersNetwork.toClient;
import static com.jubitus.travellers.net.TravellersNetwork.toServer;

public final class EquipmentMessages {

    private EquipmentMessages() {
    }

    public static void register() {
        toServer(MsgWorkerGetEquipment.class, MsgWorkerGetEquipment.Handler.class);
        toServer(MsgRequestEquipDests.class, MsgRequestEquipDests.Handler.class);
        toClient(MsgEquipDestsResponse.class, () -> new MsgEquipDestsResponse.ClientHandler());
        toServer(MsgLeaderGetEquip.class, MsgLeaderGetEquip.Handler.class);
    }
}
