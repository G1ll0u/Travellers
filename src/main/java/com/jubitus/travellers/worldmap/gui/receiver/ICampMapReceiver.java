package com.jubitus.travellers.worldmap.gui.receiver;

import com.jubitus.travellers.worldmap.net.MsgMapCampUpdate;

import java.util.List;

public interface ICampMapReceiver {
    void receiveCamps(List<MsgMapCampUpdate.Entry> entries);
}
