package com.jubitus.travellers.client.gui.worldmap.receiver;

import com.jubitus.travellers.server.net.message.home.MsgWorkerBarracksData;

/** Implemented by the barracks-assignment GUI to receive the server's barracks/worker snapshot. */
public interface IWorkerBarracksReceiver {
    void receiveBarracksData(MsgWorkerBarracksData data);
}
