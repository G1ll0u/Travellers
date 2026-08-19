package com.jubitus.travellers.client.gui.worldmap.receiver;

import com.jubitus.travellers.server.net.message.workergo.MsgWorkerGoList;

import java.util.List;

public interface IWorkerGoListReceiver {
    void receiveGoList(List<MsgWorkerGoList.Entry> entries);
}