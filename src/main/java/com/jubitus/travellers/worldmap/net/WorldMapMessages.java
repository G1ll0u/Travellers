package com.jubitus.travellers.worldmap.net;

import static com.jubitus.travellers.net.TravellersNetwork.toClient;
import static com.jubitus.travellers.net.TravellersNetwork.toServer;

public final class WorldMapMessages {

    private WorldMapMessages() {
    }

    public static void register() {
        toServer(MsgSubscribeWorkerMap.class, MsgSubscribeWorkerMap.Handler.class);
        toClient(MsgMapWorkersUpdate.class, () -> new MsgMapWorkersUpdate.ClientHandler());
        toClient(MsgMapDiscoverTile.class, () -> new MsgMapDiscoverTile.ClientHandler());
        toClient(MsgMapDiscoverySync.class, () -> new MsgMapDiscoverySync.ClientHandler());
        toServer(MsgRequestMapDiscovery.class, MsgRequestMapDiscovery.Handler.class);
        toServer(MsgWorkerMapView.class, MsgWorkerMapView.Handler.class);
        toServer(MsgRequestMapTiles.class, MsgRequestMapTiles.Handler.class);
        toClient(MsgMapTileData.class, () -> new MsgMapTileData.ClientHandler());
        toClient(MsgOpenPopulationMap.class, () -> new MsgOpenPopulationMap.Handler());
        toServer(MsgSubscribePopulationMap.class, MsgSubscribePopulationMap.Handler.class);
        toClient(MsgMapPopulationUpdate.class, () -> new MsgMapPopulationUpdate.ClientHandler());
        toServer(MsgSubscribeCampMap.class, MsgSubscribeCampMap.Handler.class);
        toClient(MsgMapCampUpdate.class, () -> new MsgMapCampUpdate.ClientHandler());
        toClient(MsgMapBattleUpdate.class, () -> new MsgMapBattleUpdate.ClientHandler());
        toClient(MsgMapBattleStrikes.class, () -> new MsgMapBattleStrikes.ClientHandler());
        toClient(MsgMapForeignUnitsUpdate.class, () -> new MsgMapForeignUnitsUpdate.ClientHandler());
    }
}
