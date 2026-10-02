package com.jubitus.travellers.worldmap.gui.receiver;

import com.jubitus.travellers.worldmap.net.MsgMapPopulationUpdate;

import java.util.List;

public interface IPopulationMapReceiver {
    void receivePopulation(List<MsgMapPopulationUpdate.Entry> entries);
}
