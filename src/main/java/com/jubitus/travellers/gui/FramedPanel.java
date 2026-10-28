package com.jubitus.travellers.gui;

import net.minecraft.client.gui.FontRenderer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** The centred, titled frame of a screen without a map (same chrome as {@link MapListLayout}). */
@SideOnly(Side.CLIENT)
public final class FramedPanel {

    private static final int SCREEN_MARGIN = 8;

    public final int x, y, w, h;
    public final int bodyX, bodyY, bodyW, bodyH;

    /** Wanted size, reduced to fit the screen. */
    public FramedPanel(int screenW, int screenH, int wantedW, int wantedH) {
        w = Math.min(wantedW, screenW - SCREEN_MARGIN * 2);
        h = Math.min(wantedH, screenH - SCREEN_MARGIN * 2);
        x = (screenW - w) / 2;
        y = (screenH - h) / 2;
        bodyX = x + MapListLayout.PAD;
        bodyY = y + MapListLayout.TITLE_H + MapListLayout.PAD;
        bodyW = w - MapListLayout.PAD * 2;
        bodyH = h - MapListLayout.TITLE_H - MapListLayout.PAD * 2;
    }

    public void draw(FontRenderer fr, String title) {
        TravellersGuiTheme.drawPanel(x, y, w, h);
        MapListLayout.drawTitle(fr, x, w, y, title);
    }
}
