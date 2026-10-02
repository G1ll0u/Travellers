package com.jubitus.travellers.debug.net;

import static com.jubitus.travellers.net.TravellersNetwork.toClient;
import static com.jubitus.travellers.net.TravellersNetwork.toServer;

public final class DebugMessages {

    private DebugMessages() {
    }

    public static void register() {
        toClient(MsgToggleFollow.class, MsgToggleFollow.Handler::new);
        toClient(MsgTraceHud.class, MsgTraceHud.Handler::new);
        toServer(MsgTerrainDebugRequest.class, MsgTerrainDebugRequest.Handler.class);
        toClient(MsgTerrainDebugMap.class, MsgTerrainDebugMap.ClientHandler::new);
        toServer(MsgItineraryDebugRequest.class, MsgItineraryDebugRequest.Handler.class);
        toClient(MsgItineraryDebugMap.class, MsgItineraryDebugMap.ClientHandler::new);
        toServer(MsgTeleportToPos.class, MsgTeleportToPos.Handler.class);
        toServer(MsgSpawnEntityAtPos.class, MsgSpawnEntityAtPos.Handler.class);
        toClient(MsgVillageDebugView.class, MsgVillageDebugView.Handler::new);
        toClient(PacketTravellerChunks.class, PacketTravellerChunksClientHandler::new);
        toServer(PacketTravellerChunksRequest.class, PacketTravellerChunksRequest.Handler.class);
    }
}
