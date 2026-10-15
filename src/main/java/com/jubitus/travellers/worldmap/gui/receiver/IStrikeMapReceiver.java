package com.jubitus.travellers.worldmap.gui.receiver;

import com.jubitus.travellers.worldmap.net.MsgMapBattleStrikes;

import java.util.List;

/** A map screen that animates the fight strikes sent by the server. */
public interface IStrikeMapReceiver {
    void receiveStrikes(int dim, List<MsgMapBattleStrikes.Entry> strikes);
}
