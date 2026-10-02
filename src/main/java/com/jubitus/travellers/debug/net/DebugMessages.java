package com.jubitus.travellers.debug.net;

import static com.jubitus.travellers.net.TravellersNetwork.toClient;
import static com.jubitus.travellers.net.TravellersNetwork.toServer;

public final class DebugMessages {

    private DebugMessages() {
    }

    public static void register() {
        toClient(MsgToggleFollow.class, () -> new MsgToggleFollow.Handler());
        toClient(MsgTraceHud.class, () -> new MsgTraceHud.Handler());
        toServer(MsgTerrainDebugRequest.class, MsgTerrainDebugRequest.Handler.class);
        toClient(MsgTerrainDebugMap.class, () -> new MsgTerrainDebugMap.ClientHandler());
        toServer(MsgItineraryDebugRequest.class, MsgItineraryDebugRequest.Handler.class);
        toClient(MsgItineraryDebugMap.class, () -> new MsgItineraryDebugMap.ClientHandler());
        toServer(MsgTeleportToPos.class, MsgTeleportToPos.Handler.class);
        toServer(MsgSpawnEntityAtPos.class, MsgSpawnEntityAtPos.Handler.class);
        toClient(MsgVillageDebugView.class, () -> new MsgVillageDebugView.Handler());
        toClient(PacketTravellerChunks.class, () -> new PacketTravellerChunksClientHandler());
        toServer(PacketTravellerChunksRequest.class, PacketTravellerChunksRequest.Handler.class);
    }
}
