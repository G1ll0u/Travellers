package com.jubitus.travellers.worldmap.gui.receiver;

import com.jubitus.travellers.worldmap.net.MsgMapWorkersUpdate;

import java.util.List;

public interface IWorkerMapReceiver {
    void receiveWorkers(List<MsgMapWorkersUpdate.Entry> workers);
}