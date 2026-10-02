package com.jubitus.travellers.worldmap.net;

import static com.jubitus.travellers.net.TravellersNetwork.toClient;
import static com.jubitus.travellers.net.TravellersNetwork.toServer;

public final class WorldMapMessages {

    private WorldMapMessages() {
    }

    public static void register() {
        toServer(MsgSubscribeWorkerMap.class, MsgSubscribeWorkerMap.Handler.class);
        toClient(MsgMapWorkersUpdate.class, MsgMapWorkersUpdate.ClientHandler::new);
        toClient(MsgMapDiscoverTile.class, MsgMapDiscoverTile.ClientHandler::new);
        toClient(MsgMapDiscoverySync.class, MsgMapDiscoverySync.ClientHandler::new);
        toServer(MsgRequestMapDiscovery.class, MsgRequestMapDiscovery.Handler.class);
        toServer(MsgWorkerMapView.class, MsgWorkerMapView.Handler.class);
        toServer(MsgRequestMapTiles.class, MsgRequestMapTiles.Handler.class);
        toClient(MsgMapTileData.class, MsgMapTileData.ClientHandler::new);
        toClient(MsgOpenPopulationMap.class, MsgOpenPopulationMap.Handler::new);
        toServer(MsgSubscribePopulationMap.class, MsgSubscribePopulationMap.Handler.class);
        toClient(MsgMapPopulationUpdate.class, MsgMapPopulationUpdate.ClientHandler::new);
        toServer(MsgSubscribeCampMap.class, MsgSubscribeCampMap.Handler.class);
        toClient(MsgMapCampUpdate.class, MsgMapCampUpdate.ClientHandler::new);
        toClient(MsgMapBattleUpdate.class, MsgMapBattleUpdate.ClientHandler::new);
        toClient(MsgMapForeignUnitsUpdate.class, MsgMapForeignUnitsUpdate.ClientHandler::new);
    }
}
