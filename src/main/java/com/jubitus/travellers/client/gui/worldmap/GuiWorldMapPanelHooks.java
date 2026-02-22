package com.jubitus.travellers.client.gui.worldmap;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class GuiWorldMapPanelHooks {
    private static GuiWorldMapPanel lastPanel = null;

    private GuiWorldMapPanelHooks() {}

    public static void setPanel(GuiWorldMapPanel panel) {
        lastPanel = panel;
    }

    public static void markDirty() {
        if (lastPanel != null) lastPanel.forceDirty();
    }
}
