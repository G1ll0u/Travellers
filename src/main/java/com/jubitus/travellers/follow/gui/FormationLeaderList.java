package com.jubitus.travellers.follow.gui;

import com.jubitus.travellers.follow.net.FormationLeaderInfo;
import com.jubitus.travellers.gui.ScrollList;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import com.jubitus.travellers.gui.WorkerHeadRenderer;
import com.jubitus.travellers.notify.client.LocationFormat;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.text.TextFormatting;

import java.util.List;

/** The Formation Hub's leader rows: head, name, follower count, distance and direction from the player. */
final class FormationLeaderList {

    private static final int ROW_H = 20;
    private static final int INNER = 4;

    private final ScrollList rows = new ScrollList(ROW_H);
    private final List<FormationLeaderInfo> leaders;

    FormationLeaderList(List<FormationLeaderInfo> leaders) {
        this.leaders = leaders;
    }

    /** Rows start at y; only whole rows fit above bottom. */
    void setBounds(int x, int y, int w, int bottom) {
        int visible = Math.max(1, (bottom - y) / ROW_H);
        rows.setBounds(x, y, w, visible * ROW_H);
    }

    /** Row under the mouse, or -1. */
    int rowAt(int mouseX, int mouseY) {
        return rows.rowAt(mouseX, mouseY, leaders.size());
    }

    void scrollBy(int wheel) {
        rows.scrollBy(wheel, leaders.size());
    }

    void ensureVisible(int index) {
        rows.ensureVisible(index);
    }

    /** selectedIndex: highlighted row, -1 for none; arrowTop/arrowBottom: where the more-rows arrows go. */
    void draw(FontRenderer fr, EntityPlayer player, int mouseX, int mouseY, boolean interactive,
              int selectedIndex, int arrowTop, int arrowBottom) {
        rows.clamp(leaders.size());
        int x = rows.x, w = rows.w;
        for (int i = 0; i < rows.rowsToDraw(leaders.size()); i++) {
            int idx = rows.scroll() + i;
            int rowTop = rows.rowTop(idx);
            boolean hovered = interactive && rows.isRowHovered(idx, mouseX, mouseY);
            boolean selected = idx == selectedIndex;
            TravellersGuiTheme.drawListRow(x + 1, rowTop, w - 2, ROW_H, hovered, selected);

            FormationLeaderInfo info = leaders.get(idx);
            int textX = x + INNER;
            if (!info.isPlayer) {
                GlStateManager.color(1f, 1f, 1f, 1f);
                WorkerHeadRenderer.draw(info.cultureId, info.textureIndex, info.ownerColor, x + INNER, rowTop + 2, 16);
                textX = x + INNER + 19;
            }
            String name = info.isPlayer ? I18n.format("travellers.worker.formation.you") : info.name;
            if (info.isVirtual) name = TextFormatting.GRAY + name;
            int maxW = x + w - INNER - textX;
            fr.drawString(fr.trimStringToWidth(name, maxW), textX, rowTop + 2,
                    selected ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT);

            String sub = info.followerCount + " ♥";
            if (!info.isPlayer) {
                double dx = info.posX - player.posX, dz = info.posZ - player.posZ;
                sub += "  " + (int) Math.sqrt(dx * dx + dz * dz) + "m " + LocationFormat.cardinal(dx, dz);
            }
            TravellersGuiTheme.drawScaledText(fr, sub, textX, rowTop + 12, 0.75f, TravellersGuiTheme.COL_TEXT_DIM);
        }
        int arrowX = x + w - INNER - 6;
        if (rows.scroll() > 0) fr.drawString("▲", arrowX, arrowTop, TravellersGuiTheme.COL_TEXT_DIM);
        if (rows.scroll() + rows.visibleRows() < leaders.size())
            fr.drawString("▼", arrowX, arrowBottom, TravellersGuiTheme.COL_TEXT_DIM);
    }
}
