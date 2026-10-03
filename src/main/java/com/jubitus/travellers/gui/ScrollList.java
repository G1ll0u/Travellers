package com.jubitus.travellers.gui;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** Geometry and scroll position of a list of fixed-height rows; the screen draws the rows themselves. */
@SideOnly(Side.CLIENT)
public final class ScrollList {

    public final int rowH;
    public int x, y, w, h;
    private int scroll;

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

    public boolean isOver(int mouseX, int mouseY) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    public boolean isRowHovered(int index, int mouseX, int mouseY) {
        int top = rowTop(index);
        return mouseX >= x && mouseX < x + w && mouseY >= top && mouseY < top + rowH;
    }

    /** Row under the mouse, or -1. */
    public int rowAt(int mouseX, int mouseY, int count) {
        if (!isOver(mouseX, mouseY)) return -1;
        int idx = scroll + (mouseY - y) / rowH;
        return idx >= 0 && idx < count ? idx : -1;
    }

    /** Themed scrollbar along the right edge, drawn only when the rows overflow. */
    public void drawScrollbar(int count) {
        TravellersGuiTheme.drawScrollbar(x + w - TravellersGuiTheme.SCROLLBAR_W, y, h,
                scroll, visibleRows(), count, false);
    }
}
