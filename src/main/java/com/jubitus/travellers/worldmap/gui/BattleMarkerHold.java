package com.jubitus.travellers.worldmap.gui;

import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.UUID;

/** Keeps a hovered battle marker where it is, so its tooltip never slides away from the mouse. */
final class BattleMarkerHold {

    // A hold not refreshed by a hover this recently is released.
    private static final long RELEASE_MS = 250L;

    @Nullable private static UUID heldId;
    @Nullable private static BlockPos heldPos;
    private static long lastHoverMs;

    private BattleMarkerHold() {
    }

    /** Called every frame the battle's tooltip is shown. */
    static void hovered(@Nullable UUID id, BlockPos pos) {
        if (id == null) return;
        if (!id.equals(heldId)) {
            heldId = id;
            heldPos = pos;
        }
        lastHoverMs = System.currentTimeMillis();
    }

    /** Where to draw a battle's marker: its held spot while hovered, else where the server puts it. */
    static BlockPos positionOf(@Nullable UUID id, BlockPos pos) {
        if (id == null || !id.equals(heldId) || heldPos == null) return pos;
        if (System.currentTimeMillis() - lastHoverMs > RELEASE_MS) {
            heldId = null;
            heldPos = null;
            return pos;
        }
        return heldPos;
    }
}
