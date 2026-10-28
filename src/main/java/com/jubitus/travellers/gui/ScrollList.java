package com.jubitus.travellers.gui;

import net.minecraft.client.gui.FontRenderer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Geometry, scroll position and scrollbar interaction of a list of fixed-height rows; the screen draws
 * the rows themselves. The scrollbar is dragged, and a click on its track jumps there.
 */
@SideOnly(Side.CLIENT)
public final class ScrollList {

    /** Row height of a plain one-line list, for the vanilla font at full size. */
    public static final int ROW_H = 13;
    /** Wider than the drawn bar so it is easy to grab. */
    private static final int GRAB_W = 8;
    /** Text inset inside a row. */
    public static final int TEXT_PAD = 3;

    public final int rowH;
    public int x, y, w, h;
    private int scroll;
    private boolean dragging;
    private int grabOffset;

    public ScrollList() {
        this(ROW_H);
    }

    public ScrollList(int rowH) {
        this.rowH = rowH;
    }

    public void setBounds(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public int visibleRows() {
        return Math.max(1, h / rowH);
    }

    public boolean overflows(int count) {
        return count > visibleRows();
    }

    /** Width left for a row: the scrollbar's strip is not used while it is shown. */
    public int rowW(int count) {
        return overflows(count) ? w - TravellersGuiTheme.SCROLLBAR_W - 1 : w;
    }

    /** Index of the first visible row. */
    public int scroll() {
        return scroll;
    }

    public void resetScroll() {
        scroll = 0;
    }

    public void clamp(int count) {
        scroll = Math.max(0, Math.min(scroll, count - visibleRows()));
    }

    /** One row per wheel click; wheel > 0 scrolls up. */
    public void scrollBy(int wheel, int count) {
        if (wheel == 0) return;
        scroll += wheel > 0 ? -1 : 1;
        clamp(count);
    }

    /** Scrolls just enough for row index to be visible. */
    public void ensureVisible(int index) {
        int vis = visibleRows();
        if (index < scroll) scroll = index;
        else if (index >= scroll + vis) scroll = index - vis + 1;
    }

    /** Number of rows to draw, starting at {@link #scroll()}. */
    public int rowsToDraw(int count) {
        return Math.max(0, Math.min(visibleRows(), count - scroll));
    }

    public int rowTop(int index) {
        return y + (index - scroll) * rowH;
    }

    /** Y of a one-line text centred in the row at rowTop. */
    public int textY(FontRenderer fr, int rowTop) {
        return rowTop + (rowH - fr.FONT_HEIGHT) / 2 + 1;
    }

    public boolean isOver(int mouseX, int mouseY) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    public boolean isRowHovered(int index, int mouseX, int mouseY) {
        int top = rowTop(index);
        return mouseX >= x && mouseX < x + w && mouseY >= top && mouseY < top + rowH
                && mouseY >= y && mouseY < y + h;
    }

    /** Row under the mouse, or -1 (the scrollbar strip is not a row). */
    public int rowAt(int mouseX, int mouseY, int count) {
        if (!isOver(mouseX, mouseY) || overScrollbar(mouseX, mouseY, count)) return -1;
        int idx = scroll + (mouseY - y) / rowH;
        return idx >= 0 && idx < count ? idx : -1;
    }

    // ── Scrollbar ─────────────────────────────────────────────────────────────

    private boolean overScrollbar(int mouseX, int mouseY, int count) {
        return overflows(count) && mouseX >= x + w - GRAB_W && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    /** Call from mouseClicked: true when the click grabbed the scrollbar (thumb or track). */
    public boolean mouseDown(int mouseX, int mouseY, int count) {
        if (!overScrollbar(mouseX, mouseY, count)) return false;
        int[] thumb = TravellersGuiTheme.scrollbarThumb(y, h, scroll, visibleRows(), count);
        boolean onThumb = thumb != null && mouseY >= thumb[0] && mouseY < thumb[0] + thumb[1];
        grabOffset = onThumb ? mouseY - thumb[0] : (thumb != null ? thumb[1] / 2 : 0);
        dragging = true;
        dragTo(mouseY, count);
        return true;
    }

    /** Call from mouseClickMove. */
    public void mouseDrag(int mouseY, int count) {
        if (dragging) dragTo(mouseY, count);
    }

    /** Call from mouseReleased. */
    public void mouseUp() {
        dragging = false;
    }

    private void dragTo(int mouseY, int count) {
        int[] thumb = TravellersGuiTheme.scrollbarThumb(y, h, scroll, visibleRows(), count);
        if (thumb == null) return;
        int track = h - thumb[1];
        int maxFirst = count - visibleRows();
        if (track <= 0 || maxFirst <= 0) return;
        float t = (mouseY - grabOffset - y) / (float) track;
        scroll = Math.max(0, Math.min(maxFirst, Math.round(t * maxFirst)));
    }

    /** Themed scrollbar along the right edge, drawn only when the rows overflow. */
    public void drawScrollbar(int count) {
        TravellersGuiTheme.drawScrollbar(x + w - TravellersGuiTheme.SCROLLBAR_W, y, h,
                scroll, visibleRows(), count, dragging);
    }
}
