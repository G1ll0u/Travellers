package com.jubitus.travellers.client.gui.worldmap.receiver;

import com.jubitus.travellers.server.map.stream.MsgMapPopulationUpdate;

import java.util.List;

public interface IPopulationMapReceiver {
    void receivePopulation(List<MsgMapPopulationUpdate.Entry> entries);
}
