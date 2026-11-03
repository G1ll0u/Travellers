package com.jubitus.travellers.gui;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.text.TextFormatting;

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

    // ── Dialogue transcript ───────────────────────────────────────────────────
    /** Accent bar + name color for the player's side of a dialogue transcript. */
    public static final int COL_DIALOGUE_PLAYER = 0xFF7FBF6A;
    /** Accent bar + name color for the traveller's side of a dialogue transcript. */
    public static final int COL_DIALOGUE_NPC    = 0xFFE0A868;

    // ── List rows ─────────────────────────────────────────────────────────────
    public static final int COL_ROW_SEL   = 0x604A3C20;
    public static final int COL_ROW_HOVER = 0x3AFFFFFF;

    // ── Feedback ──────────────────────────────────────────────────────────────
    public static final int COL_AFFORD  = 0x00FF00;
    public static final int COL_CANNOT  = 0xFF5555;

    // ── Buttons ───────────────────────────────────────────────────────────────
    public static final int COL_BTN_BG          = 0xFF2A2219;
    public static final int COL_BTN_BG_HOVER    = 0xFF382D21;
    public static final int COL_BTN_BG_OFF      = 0xFF221C15;
    public static final int COL_BTN_BORDER_OFF  = 0xFF3A3128;
    public static final int COL_BTN_TEXT_OFF    = 0xFF6B5B4A;

    /** Toggle in its "on" state. */
    public static final int COL_ACTIVE          = 0xFF7FBF6A;

    /** Destructive actions (unhire, delete...). */
    public static final int COL_DANGER_TEXT     = 0xFFE0806E;
    public static final int COL_DANGER_TEXT_HOV = 0xFFFF9E88;
    public static final int COL_DANGER_BORDER   = 0xFF6B3A2E;
    public static final int COL_DANGER_BORDER_HOV = 0xFFA8543F;

    // ── Menu-tree links ───────────────────────────────────────────────────────
    public static final int COL_LINK      = 0xFF685747;
    public static final int COL_LINK_OPEN = 0xFFFFCC88;

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

    /** Draws a title string in the main text color, bold: the one title style of every screen. */
    public static void drawTitle(FontRenderer fr, String text, int x, int y) {
        fr.drawString(TextFormatting.BOLD + text, x, y, COL_TEXT);
    }

    /** Draws a string centered on x, in the main text color. */
    public static void drawCenteredText(FontRenderer fr, String text, int centerX, int y, int color) {
        MoneyText.draw(fr, text, centerX - MoneyText.width(fr, text) / 2, y, color);
    }

    /** Draws a 1px vertical line in the given color. */
    public static void drawVLine(int x, int y1, int y2, int color) {
        if (y2 < y1) { int t = y1; y1 = y2; y2 = t; }
        Gui.drawRect(x, y1, x + 1, y2, color);
    }

    /** Draws a 1px horizontal line in the given color. */
    public static void drawHLine(int x1, int x2, int y, int color) {
        if (x2 < x1) { int t = x1; x1 = x2; x2 = t; }
        Gui.drawRect(x1, y, x2, y + 1, color);
    }

    /**
     * Draws the box of a themed button: fill + 1px border.
     * The caller draws the label; this only paints the frame so non-GuiButton widgets
     * (color swatches, sliders...) can share the exact same look.
     */
    public static void drawButtonBox(int x, int y, int w, int h, int fill, int border) {
        Gui.drawRect(x, y, x + w, y + h, fill);
        drawBorder(x, y, w, h, border);
    }

    /** Size of an item slot's box: the 16px item plus its 1px border on each side. */
    public static final int SLOT = 18;

    /** The one item-slot look; (itemX, itemY) is the item's top-left corner, as in {@code Slot.xPos/yPos}. */
    public static void drawSlot(int itemX, int itemY) {
        drawInnerPanel(itemX - 1, itemY - 1, SLOT, SLOT);
    }

    // ── Scrollbar ─────────────────────────────────────────────────────────────
    public static final int SCROLLBAR_W = 4;

    /**
     * Thumb of a scrollbar of height h at y, for a view showing {@code visible} of {@code total} units
     * (rows or pixels) starting at {@code first}: {thumbY, thumbH}, or null when everything fits.
     */
    public static int[] scrollbarThumb(int y, int h, int first, int visible, int total) {
        int maxFirst = total - visible;
        if (maxFirst <= 0 || h <= 0) return null;
        int thumbH = Math.min(h, Math.max(6, h * visible / total));
        int clamped = Math.max(0, Math.min(first, maxFirst));
        int thumbY = y + Math.round((h - thumbH) * clamped / (float) maxFirst);
        return new int[]{thumbY, thumbH};
    }

    /** The one scrollbar look: dark track, dim thumb (bright while dragged); nothing when everything fits. */
    public static void drawScrollbar(int x, int y, int h, int first, int visible, int total, boolean active) {
        int[] thumb = scrollbarThumb(y, h, first, visible, total);
        if (thumb == null) return;
        Gui.drawRect(x, y, x + SCROLLBAR_W, y + h, COL_PANEL);
        Gui.drawRect(x, thumb[0], x + SCROLLBAR_W, thumb[0] + thumb[1], active ? COL_TEXT_HOV : COL_TEXT_DIM);
    }

    // ── Text ──────────────────────────────────────────────────────────────────

    /** Draws text scaled down (e.g. 0.85 for list rows) with its top-left corner at x, y. */
    public static void drawScaledText(FontRenderer fr, String text, int x, int y, float scale, int color) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0f);
        GlStateManager.scale(scale, scale, 1f);
        MoneyText.draw(fr, text, 0, 0, color);
        GlStateManager.popMatrix();
    }

    /** Fits "name + suffix" in maxWidth by cutting the middle of the name ("Long...name (120m)"); the suffix is always kept. */
    public static String shortenMiddle(FontRenderer fr, String name, String suffix, int maxWidth) {
        if (fr.getStringWidth(name + suffix) <= maxWidth) return name + suffix;
        int suffixWidth = fr.getStringWidth(suffix);
        if (suffixWidth >= maxWidth) return fr.trimStringToWidth(suffix, maxWidth);
        int remaining = maxWidth - suffixWidth;
        String ellipsis = "...";
        int ellWidth = fr.getStringWidth(ellipsis);
        if (ellWidth >= remaining) return suffix;
        int half = (remaining - ellWidth) / 2;
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            if (fr.getStringWidth(prefix + String.valueOf(name.charAt(i))) > half) break;
            prefix.append(name.charAt(i));
        }
        StringBuilder tail = new StringBuilder();
        for (int i = name.length() - 1; i >= 0; i--) {
            String candidate = name.charAt(i) + tail.toString();
            if (fr.getStringWidth(prefix + ellipsis + candidate) > remaining) break;
            tail.insert(0, name.charAt(i));
        }
        return prefix + ellipsis + tail + suffix;
    }

    /** Trims a label to fit maxWidth, appending a dot when it had to be cut. */
    public static String trimLabel(FontRenderer fr, String text, int maxWidth) {
        if (MoneyText.width(fr, text) <= maxWidth) return text;
        String cut = MoneyText.trimToWidth(fr, text, Math.max(0, maxWidth - fr.getStringWidth(".")));
        return cut + ".";
    }
}
