package com.jubitus.travellers.client.gui.worldmap.receiver;

import com.jubitus.travellers.server.map.stream.MsgMapCampUpdate;

import java.util.List;

public interface ICampMapReceiver {
    void receiveCamps(List<MsgMapCampUpdate.Entry> entries);
}
