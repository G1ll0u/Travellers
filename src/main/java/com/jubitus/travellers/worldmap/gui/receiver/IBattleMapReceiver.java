package com.jubitus.travellers.worldmap.gui.receiver;

import com.jubitus.travellers.worldmap.net.MsgMapBattleUpdate;

import java.util.List;

/** Implemented by map GUIs that display live virtual battle markers. */
public interface IBattleMapReceiver {
    void receiveBattles(List<MsgMapBattleUpdate.Entry> entries);
}
