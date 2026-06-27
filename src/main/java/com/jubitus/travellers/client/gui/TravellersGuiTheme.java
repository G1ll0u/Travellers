package com.jubitus.travellers.client.gui;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;

/**
 * Central style constants and rendering helpers shared across all mod GUIs.
 * To retheme the mod, change values here — all GUIs update automatically.
 */
public final class TravellersGuiTheme {

    // ── Background / panels ───────────────────────────────────────────────────
    public static final int COL_BG     = 0xFF2A2219;
    public static final int COL_PANEL  = 0xFF1E1A14;
    public static final int COL_BORDER = 0xFF685747;

    // ── Text ──────────────────────────────────────────────────────────────────
    public static final int COL_TEXT     = 0xF2D2B9;
    public static final int COL_TEXT_DIM = 0xFFA08060;
    public static final int COL_TEXT_HOV = 0xFFFFCC88;

    // ── List rows ─────────────────────────────────────────────────────────────
    public static final int COL_ROW_SEL   = 0x604A3C20;
    public static final int COL_ROW_HOVER = 0x3AFFFFFF;

    // ── Feedback ──────────────────────────────────────────────────────────────
    public static final int COL_AFFORD  = 0x00FF00;
    public static final int COL_CANNOT  = 0xFF5555;

    private TravellersGuiTheme() {}

    // ── Rendering helpers ─────────────────────────────────────────────────────

    /** Draws the main themed panel: dark fill + 1px border. */
    public static void drawPanel(int x, int y, int w, int h) {
        Gui.drawRect(x, y, x + w, y + h, COL_BG);
        drawBorder(x, y, w, h);
    }

    /** Draws a darker inner panel: very dark fill + 1px border. */
    public static void drawInnerPanel(int x, int y, int w, int h) {
        Gui.drawRect(x, y, x + w, y + h, COL_PANEL);
        drawBorder(x, y, w, h);
    }

    /** Draws a 1px themed border around (x,y,w,h). */
    public static void drawBorder(int x, int y, int w, int h) {
        drawBorder(x, y, w, h, COL_BORDER);
    }

    /** Draws a 1px border of arbitrary color. */
    public static void drawBorder(int x, int y, int w, int h, int color) {
        Gui.drawRect(x,         y,         x + w,     y + 1,     color);
        Gui.drawRect(x,         y + h - 1, x + w,     y + h,     color);
        Gui.drawRect(x,         y,         x + 1,     y + h,     color);
        Gui.drawRect(x + w - 1, y,         x + w,     y + h,     color);
    }

    /**
     * Draws a themed frame around a map widget.
     * Pass the map's own x/y/w/h; the frame renders 3px outside the map bounds.
     */
    public static void drawMapFrame(int mapX, int mapY, int mapW, int mapH) {
        int x = mapX - 3, y = mapY - 3, w = mapW + 6, h = mapH + 6;
        Gui.drawRect(x, y, x + w, y + h, COL_PANEL);
        drawBorder(x, y, w, h);
    }

    /**
     * Draws a list row background (selected / hovered / neutral).
     * Draws nothing if the row is neither hovered nor selected.
     */
    public static void drawListRow(int x, int y, int w, int h, boolean hovered, boolean selected) {
        int color = selected ? COL_ROW_SEL : (hovered ? COL_ROW_HOVER : 0);
        if (color != 0) Gui.drawRect(x, y, x + w, y + h, color);
    }

    /** Draws a 1px horizontal separator in the border color. */
    public static void drawSeparator(int x, int y, int w) {
        Gui.drawRect(x, y, x + w, y + 1, COL_BORDER);
    }

    /** Draws a title string in the main text color. */
    public static void drawTitle(FontRenderer fr, String text, int x, int y) {
        fr.drawString(text, x, y, COL_TEXT);
    }
}
