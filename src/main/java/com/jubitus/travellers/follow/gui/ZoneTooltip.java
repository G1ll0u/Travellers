package com.jubitus.travellers.follow.gui;

import com.jubitus.travellers.follow.net.FormationZoneInfo;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.resources.I18n;

import java.util.ArrayList;
import java.util.List;

/** Hovering a Barrack or Stable (list row or map marker): its occupancy and, for workers, their health. */
public final class ZoneTooltip {

    private static final int W = 156;
    private static final int PAD = 5;
    private static final int LINE = 11;
    private static final int BAR_H = 6;
    // Above tooltips (z 300), like the group summary.
    private static final float Z = 350F;

    private ZoneTooltip() {}

    public static void draw(FontRenderer fr, FormationZoneInfo z, int mouseX, int mouseY, int screenW, int screenH) {
        String key = "travellers.worker.formation.zone.";
        String size = z.stable
                ? I18n.format(key + "horses", z.horses, z.capacity)
                : I18n.format(key + "beds", z.bound, z.capacity);
        List<String> lines = new ArrayList<>();
        boolean health = false;
        if (z.stable) {
            lines.add(I18n.format(key + "horse_state", z.tamed, z.saddled));
            lines.add(I18n.format(key + "hands", z.bound, z.here));
        } else if (z.bound == 0) {
            lines.add(I18n.format(key + "nobody"));
        } else {
            lines.add(I18n.format(key + "here_away", z.here, z.bound - z.here));
            lines.add(I18n.format(key + "at_rest", z.members.size()));
            health = true;
        }

        int h = PAD + LINE + lines.size() * LINE + (health ? LINE + (z.gear.wounded > 0 ? LINE : 0) : 0) + PAD;
        int x = mouseX + 12;
        int y = mouseY - 12;
        if (x + W > screenW - 2) x = mouseX - W - 12;
        if (y + h > screenH - 2) y = screenH - h - 2;
        if (y < 2) y = 2;

        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.color(1F, 1F, 1F, 1F);
        GlStateManager.pushMatrix();
        GlStateManager.translate(0F, 0F, Z);

        Gui.drawRect(x, y, x + W, y + h, 0xF0100C08);
        TravellersGuiTheme.drawBorder(x, y, W, h, TravellersGuiTheme.COL_BORDER);

        int left = x + PAD;
        int right = x + W - PAD;
        int rowY = y + PAD;
        fr.drawString(fr.trimStringToWidth(z.name, W - PAD * 3 - fr.getStringWidth(size)), left, rowY, TravellersGuiTheme.COL_TEXT_HOV);
        fr.drawString(size, right - fr.getStringWidth(size), rowY, TravellersGuiTheme.COL_TEXT_DIM);
        rowY += LINE;
        for (String line : lines) {
            fr.drawString(fr.trimStringToWidth(line, W - PAD * 2), left, rowY, TravellersGuiTheme.COL_TEXT);
            rowY += LINE;
        }

        if (health) {
            int labelW = fr.getStringWidth("♥") + 4;
            int pctW = fr.getStringWidth("100%");
            int barX = left + labelW;
            int barR = right - pctW - 4;
            float avg = z.gear.averageHealth();
            fr.drawString("♥", left, rowY, 0xFFFF5555);
            GroupSummaryTooltip.drawBarFrame(barX, rowY + 1, barR);
            Gui.drawRect(barX + 1, rowY + 2, barX + 1 + Math.round((barR - barX - 2) * avg), rowY + BAR_H,
                    GroupSummaryTooltip.healthColour(avg));
            String pct = Math.round(avg * 100F) + "%";
            fr.drawString(pct, right - fr.getStringWidth(pct), rowY, TravellersGuiTheme.COL_TEXT);
            rowY += LINE;
            if (z.gear.wounded > 0) {
                fr.drawString(I18n.format("travellers.worker.formation.summary.wounded", z.gear.wounded), barX, rowY,
                        TravellersGuiTheme.COL_TEXT_DIM);
            }
        }

        GlStateManager.popMatrix();
        GlStateManager.color(1F, 1F, 1F, 1F);
    }
}
