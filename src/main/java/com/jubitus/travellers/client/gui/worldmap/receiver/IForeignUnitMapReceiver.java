package com.jubitus.travellers.client.gui.worldmap.receiver;

import com.jubitus.travellers.server.map.stream.MsgMapForeignUnitsUpdate;

import java.util.List;

/** Implemented by map GUIs that display foreign (not the player's own) discovered units. */
public interface IForeignUnitMapReceiver {
    void receiveForeignUnits(List<MsgMapForeignUnitsUpdate.Entry> entries);
}
