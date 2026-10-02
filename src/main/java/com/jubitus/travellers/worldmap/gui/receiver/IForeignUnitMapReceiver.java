package com.jubitus.travellers.worldmap.gui.receiver;

import com.jubitus.travellers.worldmap.net.MsgMapForeignUnitsUpdate;

import java.util.List;

/** Implemented by map GUIs that display foreign (not the player's own) discovered units. */
public interface IForeignUnitMapReceiver {
    void receiveForeignUnits(List<MsgMapForeignUnitsUpdate.Entry> entries);
}
