package com.jubitus.travellers.client.gui.worldmap.receiver;

import com.jubitus.travellers.server.map.stream.MsgMapBattleUpdate;

import java.util.List;

/** Implemented by map GUIs that display live virtual battle markers. */
public interface IBattleMapReceiver {
    void receiveBattles(List<MsgMapBattleUpdate.Entry> entries);
}
