package com.jubitus.travellers.worldmap.gui.receiver;

import com.jubitus.travellers.zone.net.MsgWorkerBarracksData;

/** Implemented by the barracks-assignment GUI to receive the server's barracks/worker snapshot. */
public interface IWorkerBarracksReceiver {
    void receiveBarracksData(MsgWorkerBarracksData data);
}
