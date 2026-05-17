package com.jubitus.travellers.client.gui.worldmap;

import com.jubitus.travellers.server.map.MsgMapWorkersUpdate;

import java.util.List;

public interface IWorkerMapReceiver {
    void receiveWorkers(List<MsgMapWorkersUpdate.Entry> workers);
}