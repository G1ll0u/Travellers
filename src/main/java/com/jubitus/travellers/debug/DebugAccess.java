package com.jubitus.travellers.debug;

import net.minecraft.entity.player.EntityPlayerMP;

/** The one permission test for every debug message and command: operators only. */
public final class DebugAccess {

    private DebugAccess() {
    }

    public static boolean allowed(EntityPlayerMP player) {
        return player.canUseCommand(2, "travellersdebug");
    }
}
