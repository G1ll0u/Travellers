package com.jubitus.travellers.client.gui.worldmap.receiver;

import com.jubitus.travellers.server.net.message.zone.MsgWorkerZoneData;

/** Implemented by the zone-assignment GUI's Duty tab to receive the server's stable/worker snapshot. */
public interface IWorkerZoneDutyReceiver {
    void receiveZoneDutyData(MsgWorkerZoneData data);
}
