package com.jubitus.travellers.follow.gui;

import com.jubitus.travellers.follow.net.FormationZoneInfo;
import com.jubitus.travellers.gui.ScrollList;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import com.jubitus.travellers.notify.client.LocationFormat;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.entity.player.EntityPlayer;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/** The Formation Hub's Barrack rows under the leaders: name, resting henchmen, distance and direction from the player. */
final class FormationBarrackList {

    private static final int INNER = 4;

    private final ScrollList rows = new ScrollList(FormationLeaderList.ROW_H);
    private final List<FormationZoneInfo> barracks;

    FormationBarrackList(List<FormationZoneInfo> barracks) {
        this.barracks = barracks;
    }

    /** Rows start at y and take exactly {@code visibleRows} rows. */
    void setBounds(int x, int y, int w, int visibleRows) {
        rows.setBounds(x, y, w, visibleRows * FormationLeaderList.ROW_H);
    }

    int rowAt(int mouseX, int mouseY) {
        return rows.rowAt(mouseX, mouseY, barracks.size());
    }

    @Nullable
    FormationZoneInfo infoAt(int mouseX, int mouseY) {
        int idx = rowAt(mouseX, mouseY);
        return idx >= 0 ? barracks.get(idx) : null;
    }

    boolean isOver(int mouseX, int mouseY) {
        return rows.isOver(mouseX, mouseY);
    }

    void scrollBy(int wheel) {
        rows.scrollBy(wheel, barracks.size());
    }

    /** selectedId: the Barrack whose residents are selected, or null. */
    void draw(FontRenderer fr, EntityPlayer player, int mouseX, int mouseY, boolean interactive,
              @Nullable UUID selectedId) {
        rows.clamp(barracks.size());
        int x = rows.x, w = rows.w;
        int maxW = w - INNER * 2;
        for (int i = 0; i < rows.rowsToDraw(barracks.size()); i++) {
            int idx = rows.scroll() + i;
            int rowTop = rows.rowTop(idx);
            FormationZoneInfo info = barracks.get(idx);
            boolean selected = info.id.equals(selectedId);
            TravellersGuiTheme.drawListRow(x + 1, rowTop, w - 2, FormationLeaderList.ROW_H,
                    interactive && rows.isRowHovered(idx, mouseX, mouseY), selected);

            int nameColour = selected ? TravellersGuiTheme.COL_TEXT_HOV
                    : info.members.isEmpty() ? TravellersGuiTheme.COL_TEXT_DIM : TravellersGuiTheme.COL_TEXT;
            fr.drawString(fr.trimStringToWidth(info.name, maxW), x + INNER, rowTop + 3, nameColour);

            double dx = info.center.getX() + 0.5 - player.posX, dz = info.center.getZ() + 0.5 - player.posZ;
            String sub = info.members.size() + " ♥  " + (int) Math.sqrt(dx * dx + dz * dz) + "m " + LocationFormat.cardinal(dx, dz);
            fr.drawString(fr.trimStringToWidth(sub, maxW), x + INNER, rowTop + 13, TravellersGuiTheme.COL_TEXT_DIM);
        }
        rows.drawScrollbar(barracks.size());
    }
}
