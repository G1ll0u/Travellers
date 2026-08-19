package com.jubitus.travellers.client.gui.worldmap.receiver;

import com.jubitus.travellers.server.map.stream.MsgMapWorkersUpdate;

import java.util.List;

public interface IWorkerMapReceiver {
    void receiveWorkers(List<MsgMapWorkersUpdate.Entry> workers);
}