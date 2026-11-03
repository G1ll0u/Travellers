package com.jubitus.travellers.gui;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * The one layout of every "map + list" screen: a framed panel with a title, the map on the left, a
 * side column on the right (list box on top, button rows at the bottom). The map box and the list box
 * share their top edge, and the map's navigation row sits on the side column's last button row, so the
 * edges always line up. Everything is computed from the real screen size, so it follows the GUI scale.
 */
@SideOnly(Side.CLIENT)
public final class MapListLayout {

    private static final int SCREEN_MARGIN = 8;
    private static final int MAX_W = 520, MAX_H = 300;
    public static final int PAD = 6, GAP = 6, TITLE_H = 18, BTN_H = 20, BTN_GAP = 4;
    /** Space the map's own navigation row takes under the map box. */
    private static final int NAV_H = BTN_H + BTN_GAP;
    private static final int MIN_MAP_W = 150;

    public final int frameX, frameY, frameW, frameH;
    public final int bodyX, bodyY, bodyW, bodyH;
    public final int mapBoxX, mapBoxY, mapBoxW, mapBoxH;
    public final int sideX, sideW;
    public final int listBoxX, listBoxY, listBoxW, listBoxH;
    private final int buttonRows;

    /**
     * @param sideWanted  preferred width of the side column (reduced if the map would get too narrow)
     * @param buttonRows  rows of buttons under the list (at least 1: the last one is level with the map's nav row)
     */
    public MapListLayout(int screenW, int screenH, int sideWanted, int buttonRows) {
        this.buttonRows = Math.max(1, buttonRows);
        frameW = Math.min(MAX_W, screenW - SCREEN_MARGIN * 2);
        frameH = Math.min(MAX_H, screenH - SCREEN_MARGIN * 2);
        frameX = (screenW - frameW) / 2;
        frameY = (screenH - frameH) / 2;
        bodyX = frameX + PAD;
        bodyY = frameY + TITLE_H + PAD;
        bodyW = frameW - PAD * 2;
        bodyH = frameH - TITLE_H - PAD * 2;

        sideW = Math.max(60, Math.min(sideWanted, bodyW - GAP - MIN_MAP_W));
        sideX = bodyX + bodyW - sideW;

        mapBoxX = bodyX;
        mapBoxY = bodyY;
        mapBoxW = sideX - GAP - bodyX;
        mapBoxH = bodyH - NAV_H;

        listBoxX = sideX;
        listBoxY = bodyY;
        listBoxW = sideW;
        listBoxH = bodyH - this.buttonRows * (BTN_H + BTN_GAP);
    }

    // The boxes have a 1px border: what they hold lives one pixel inside.
    public int mapX() { return mapBoxX + 1; }
    public int mapY() { return mapBoxY + 1; }
    public int mapW() { return mapBoxW - 2; }
    public int mapH() { return mapBoxH - 2; }

    public int listX() { return listBoxX + 1; }
    public int listY() { return listBoxY + 1; }
    public int listW() { return listBoxW - 2; }
    public int listH() { return listBoxH - 2; }

    /** Top of the map's navigation row (Center): level with the side column's last button row. */
    public int navRowY() {
        return bodyY + bodyH - BTN_H;
    }

    /** Top of side button row {@code row} (0 = just under the list). */
    public int sideRowY(int row) {
        return listBoxY + listBoxH + BTN_GAP + row * (BTN_H + BTN_GAP);
    }

    /** X of button {@code index} when the side column is split in {@code count} equal buttons. */
    public int sideButtonX(int index, int count) {
        return sideX + index * (sideButtonW(count) + BTN_GAP);
    }

    public int sideButtonW(int count) {
        return (sideW - (count - 1) * BTN_GAP) / count;
    }

    /** Frame, bold title and separator, then the two boxes (map and list) behind their content. */
    public void drawChrome(FontRenderer fr, String title) {
        TravellersGuiTheme.drawPanel(frameX, frameY, frameW, frameH);
        drawTitle(fr, frameX, frameW, frameY, title);
        TravellersGuiTheme.drawInnerPanel(mapBoxX, mapBoxY, mapBoxW, mapBoxH);
        TravellersGuiTheme.drawInnerPanel(listBoxX, listBoxY, listBoxW, listBoxH);
    }

    /** The title strip every framed screen shares: bold, left, with a separator under it. */
    public static void drawTitle(FontRenderer fr, int frameX, int frameW, int frameY, String title) {
        fr.drawString(net.minecraft.util.text.TextFormatting.BOLD + fr.trimStringToWidth(title, frameW - PAD * 2),
                frameX + PAD, frameY + (TITLE_H - fr.FONT_HEIGHT) / 2 + 1, TravellersGuiTheme.COL_TEXT);
        Gui.drawRect(frameX + 1, frameY + TITLE_H, frameX + frameW - 1, frameY + TITLE_H + 1, TravellersGuiTheme.COL_BORDER);
    }
}
