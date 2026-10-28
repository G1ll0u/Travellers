package com.jubitus.travellers.worldmap.gui.receiver;

import com.jubitus.travellers.worldmap.gui.WorkerMapView;
import com.jubitus.travellers.worldmap.net.MsgMapBattleStrikes;
import com.jubitus.travellers.worldmap.net.MsgMapBattleUpdate;
import com.jubitus.travellers.worldmap.net.MsgMapForeignUnitsUpdate;
import com.jubitus.travellers.worldmap.net.MsgMapWorkersUpdate;

import java.util.List;

/**
 * A screen with a {@link WorkerMapView}: every live map feed (workers, battles, foreign units, strikes)
 * lands in the view, so all such screens show the same things.
 */
public interface IWorkerMapScreen extends IWorkerMapReceiver, IBattleMapReceiver, IForeignUnitMapReceiver, IStrikeMapReceiver {

    WorkerMapView mapView();

    @Override
    default void receiveWorkers(List<MsgMapWorkersUpdate.Entry> workers) {
        mapView().data.setWorkers(workers);
    }

    @Override
    default void receiveBattles(List<MsgMapBattleUpdate.Entry> entries) {
        mapView().data.setBattles(entries);
    }

    @Override
    default void receiveForeignUnits(List<MsgMapForeignUnitsUpdate.Entry> entries) {
        mapView().data.setForeignUnits(entries);
    }

    @Override
    default void receiveStrikes(int dim, List<MsgMapBattleStrikes.Entry> strikes) {
        mapView().controller.panel.receiveStrikes(dim, strikes);
    }
}
