package com.jubitus.travellers.worldmap.gui.receiver;

import com.jubitus.travellers.worker.net.MsgWorkerGoList;

import java.util.List;

public interface IWorkerGoListReceiver {
    void receiveGoList(List<MsgWorkerGoList.Entry> entries);
}