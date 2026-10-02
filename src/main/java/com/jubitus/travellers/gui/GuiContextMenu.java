package com.jubitus.travellers.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Small right-click menu drawn by a screen on top of everything else: section headers, items, and
 * greyed items that explain on hover why they can't be used. While open it swallows every click,
 * so closing it by clicking elsewhere never also selects or orders something.
 */
public final class GuiContextMenu {

    private enum Kind { HEADER, ITEM, DISABLED }

    private static final class Row {
        final Kind kind;
        final String text;
        @Nullable final String reason;
        @Nullable final Runnable action;

        Row(Kind kind, String text, @Nullable String reason, @Nullable Runnable action) {
            this.kind = kind;
            this.text = text;
            this.reason = reason;
            this.action = action;
        }
    }

    private static final int PAD_X = 6;
    private static final int ROW_H = 12;
    private static final int SECTION_GAP = 4;
    private static final int REASON_W = 150;
    // Above tooltips (z 300), under notification toasts (z 400).
    private static final float Z = 350F;

    private final List<Row> rows = new ArrayList<>();
    private boolean open;
    private int x, y, w, h;

    public GuiContextMenu header(String text) {
        rows.add(new Row(Kind.HEADER, text, null, null));
        return this;
    }

    public GuiContextMenu item(String text, Runnable action) {
        rows.add(new Row(Kind.ITEM, text, null, action));
        return this;
    }

    public GuiContextMenu disabled(String text, @Nullable String reason) {
        rows.add(new Row(Kind.DISABLED, text, reason, null));
        return this;
    }

    /** Adds {@code text} as an item, or greyed with {@code reason} when the reason is not null. */
    public GuiContextMenu itemUnless(@Nullable String reason, String text, Runnable action) {
        return reason == null ? item(text, action) : disabled(text, reason);
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /** Opens at the cursor, kept fully on screen. Rows added since the last close are shown. */
    public void open(int mouseX, int mouseY, int screenW, int screenH) {
        if (rows.isEmpty()) return;
        FontRenderer fr = Minecraft.getMinecraft().fontRenderer;
        int textW = 0;
        h = 2;
        for (int i = 0; i < rows.size(); i++) {
            textW = Math.max(textW, fr.getStringWidth(rows.get(i).text));
            if (rows.get(i).kind == Kind.HEADER && i > 0) h += SECTION_GAP;
            h += ROW_H;
        }
        h += 2;
        w = textW + PAD_X * 2;
        x = mouseX + 2;
        y = mouseY + 2;
        if (x + w > screenW - 2) x = Math.max(2, mouseX - w - 2);
        if (y + h > screenH - 2) y = Math.max(2, screenH - h - 2);
        open = true;
    }

    public boolean isOpen() {
        return open;
    }

    public void close() {
        open = false;
        rows.clear();
    }

    /** While open, every click is consumed: an item runs, anything else only closes the menu. */
    public boolean mouseClicked(int mouseX, int mouseY) {
        if (!open) return false;
        Row row = rowAt(mouseX, mouseY);
        Runnable action = row != null && row.kind == Kind.ITEM ? row.action : null;
        if (row != null && row.kind != Kind.ITEM) return true; // a header or greyed item: stay open
        close();
        if (action != null) action.run();
        return true;
    }

    public void draw(int mouseX, int mouseY) {
        if (!open) return;
        FontRenderer fr = Minecraft.getMinecraft().fontRenderer;

        // A tooltip drawn before us leaves lighting/depth on, which tints flat quads.
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.color(1F, 1F, 1F, 1F);
        GlStateManager.pushMatrix();
        GlStateManager.translate(0F, 0F, Z);

        Gui.drawRect(x + 2, y + 2, x + w + 2, y + h + 2, 0x60000000);
        Gui.drawRect(x, y, x + w, y + h, TravellersGuiTheme.COL_BG);
        TravellersGuiTheme.drawBorder(x, y, w, h, TravellersGuiTheme.COL_BORDER);

        Row hovered = rowAt(mouseX, mouseY);
        int rowY = y + 2;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.kind == Kind.HEADER && i > 0) {
                TravellersGuiTheme.drawHLine(x + 3, x + w - 3, rowY + SECTION_GAP / 2 - 1, TravellersGuiTheme.COL_BORDER);
                rowY += SECTION_GAP;
            }
            int textY = rowY + (ROW_H - fr.FONT_HEIGHT) / 2 + 1;
            switch (row.kind) {
                case HEADER:
                    fr.drawString(row.text, x + PAD_X - 2, textY, TravellersGuiTheme.COL_TEXT_DIM);
                    break;
                case ITEM:
                    if (row == hovered) Gui.drawRect(x + 1, rowY, x + w - 1, rowY + ROW_H, TravellersGuiTheme.COL_ROW_HOVER);
                    fr.drawString(row.text, x + PAD_X, textY,
                            row == hovered ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT);
                    break;
                case DISABLED:
                    fr.drawString(row.text, x + PAD_X, textY, TravellersGuiTheme.COL_BTN_TEXT_OFF);
                    break;
            }
            rowY += ROW_H;
        }

        if (hovered != null && hovered.kind == Kind.DISABLED && hovered.reason != null) {
            drawReason(fr, hovered.reason, mouseX, mouseY);
        }

        GlStateManager.popMatrix();
        GlStateManager.color(1F, 1F, 1F, 1F);
    }

    private void drawReason(FontRenderer fr, String reason, int mouseX, int mouseY) {
        List<String> lines = fr.listFormattedStringToWidth(reason, REASON_W);
        int bw = 0;
        for (String l : lines) bw = Math.max(bw, fr.getStringWidth(l));
        bw += 8;
        int bh = lines.size() * (fr.FONT_HEIGHT + 1) + 6;
        int bx = x + w + 4;
        if (bx + bw > Minecraft.getMinecraft().currentScreen.width - 2) bx = x - bw - 4;
        int by = mouseY - bh / 2;
        Gui.drawRect(bx, by, bx + bw, by + bh, 0xF0100C08);
        TravellersGuiTheme.drawBorder(bx, by, bw, bh, TravellersGuiTheme.COL_BORDER);
        for (int i = 0; i < lines.size(); i++) {
            fr.drawString(lines.get(i), bx + 4, by + 4 + i * (fr.FONT_HEIGHT + 1), TravellersGuiTheme.COL_TEXT);
        }
    }

    @Nullable
    private Row rowAt(int mouseX, int mouseY) {
        if (mouseX < x || mouseX >= x + w || mouseY < y || mouseY >= y + h) return null;
        int rowY = y + 2;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.kind == Kind.HEADER && i > 0) rowY += SECTION_GAP;
            if (mouseY >= rowY && mouseY < rowY + ROW_H) return row;
            rowY += ROW_H;
        }
        return null;
    }
}
