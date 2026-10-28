package com.jubitus.travellers.follow.gui;

import com.jubitus.travellers.follow.net.FormationLeaderInfo;
import com.jubitus.travellers.follow.net.GroupGearSummary;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.resources.I18n;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

import java.util.Locale;

/** Hovering a leader row: the group's size, health, weapon counts and armour, drawn over everything. */
final class GroupSummaryTooltip {

    private static final int W = 156;
    private static final int PAD = 5;
    private static final int LINE = 11;
    private static final int ICON = 16;
    private static final int BAR_H = 6;
    private static final int LEGEND_LINE = 10;
    private static final int SWATCH = 5;
    // Above tooltips (z 300), like the orders menu.
    private static final float Z = 350F;

    private static final ItemStack[] ICONS = {
            new ItemStack(Items.IRON_SWORD), new ItemStack(Items.BOW), new ItemStack(Items.SHIELD), new ItemStack(Items.SADDLE)
    };
    // Per GroupGearSummary.Armour, best first.
    private static final int[] ARMOUR_COLOURS = {
            0xFF5DD8D0, 0xFFD8D8D8, 0xFF8C8C8C, 0xFFE8C040, 0xFFA0683A, 0xFF9070C0, TravellersGuiTheme.COL_BTN_TEXT_OFF
    };

    private GroupSummaryTooltip() {}

    static void draw(FontRenderer fr, FormationLeaderInfo info, int mouseX, int mouseY, int screenW, int screenH) {
        GroupGearSummary g = info.gear;
        if (g.members == 0) return;

        String title = info.isPlayer ? I18n.format("travellers.worker.formation.you") : info.name;
        String size = I18n.format("travellers.worker.formation.summary.members", g.members);
        String wounded = g.wounded > 0 ? I18n.format("travellers.worker.formation.summary.wounded", g.wounded) : null;
        String armourLabel = I18n.format("travellers.worker.formation.summary.armour");

        int h = PAD + LINE                      // title
                + LINE + (wounded != null ? LINE - 2 : 0) // health
                + ICON + 4                      // weapon counts
                + LINE                          // armour bar
                + legend(fr, g, 0, 0, W - PAD * 2, false) * LEGEND_LINE + PAD;
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
        fr.drawString(fr.trimStringToWidth(title, W - PAD * 3 - fr.getStringWidth(size)), left, rowY, TravellersGuiTheme.COL_TEXT_HOV);
        fr.drawString(size, right - fr.getStringWidth(size), rowY, TravellersGuiTheme.COL_TEXT_DIM);
        rowY += LINE;

        // Both bars share the same left and right edges.
        int labelW = Math.max(fr.getStringWidth("♥"), fr.getStringWidth(armourLabel)) + 4;
        int pctW = fr.getStringWidth("100%");
        int barX = left + labelW;
        int barR = right - pctW - 4;

        float health = g.averageHealth();
        fr.drawString("♥", left, rowY, 0xFFFF5555);
        drawBarFrame(barX, rowY + 1, barR);
        Gui.drawRect(barX + 1, rowY + 2, barX + 1 + Math.round((barR - barX - 2) * health), rowY + BAR_H, healthColour(health));
        String pct = Math.round(health * 100F) + "%";
        fr.drawString(pct, right - fr.getStringWidth(pct), rowY, TravellersGuiTheme.COL_TEXT);
        rowY += LINE;
        if (wounded != null) {
            TravellersGuiTheme.drawScaledText(fr, wounded, barX, rowY - 2, 0.75F, TravellersGuiTheme.COL_TEXT_DIM);
            rowY += LINE - 2;
        }

        int[] counts = {g.melee, g.bow, g.shield, g.horse};
        int cellW = (W - PAD * 2) / counts.length;
        for (int i = 0; i < counts.length; i++) {
            fr.drawString(Integer.toString(counts[i]), left + i * cellW + ICON + 2, rowY + (ICON - fr.FONT_HEIGHT) / 2 + 1,
                    counts[i] > 0 ? TravellersGuiTheme.COL_TEXT : TravellersGuiTheme.COL_BTN_TEXT_OFF);
        }
        drawIcons(left, rowY, cellW);
        rowY += ICON + 4;

        fr.drawString(armourLabel, left, rowY, TravellersGuiTheme.COL_TEXT);
        drawBarFrame(barX, rowY + 1, barR);
        int segX = barX + 1;
        int inner = barR - barX - 2;
        int done = 0;
        for (int t = 0; t < g.armour.length; t++) {
            if (g.armour[t] == 0) continue;
            done += g.armour[t];
            int end = barX + 1 + Math.round(inner * done / (float) g.members);
            Gui.drawRect(segX, rowY + 2, end, rowY + BAR_H, ARMOUR_COLOURS[t]);
            segX = end;
        }
        rowY += LINE;

        legend(fr, g, left, rowY, W - PAD * 2, true);

        GlStateManager.popMatrix();
        GlStateManager.color(1F, 1F, 1F, 1F);
    }

    static void drawBarFrame(int x, int y, int right) {
        Gui.drawRect(x, y, right, y + BAR_H, 0xFF000000);
        TravellersGuiTheme.drawBorder(x, y, right - x, BAR_H, TravellersGuiTheme.COL_BORDER);
    }

    static int healthColour(float h) {
        return h > 0.66F ? TravellersGuiTheme.COL_ACTIVE : h > 0.33F ? 0xFFE0C040 : 0xFFE05040;
    }

    private static void drawIcons(int left, int y, int cellW) {
        RenderItem ri = Minecraft.getMinecraft().getRenderItem();
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableDepth();
        for (int i = 0; i < ICONS.length; i++) ri.renderItemAndEffectIntoGUI(ICONS[i], left + i * cellW, y);
        GlStateManager.disableDepth();
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableLighting();
    }

    /**
     * The armour legend, best first: a swatch of the bar colour then "Iron 40%", wrapped to {@code width}.
     * Draws only when {@code draw}; returns the number of lines either way.
     */
    private static int legend(FontRenderer fr, GroupGearSummary g, int x, int y, int width, boolean draw) {
        GroupGearSummary.Armour[] tiers = GroupGearSummary.Armour.values();
        int lines = 1;
        int cx = 0;
        for (int t = 0; t < tiers.length; t++) {
            if (g.armour[t] == 0) continue;
            String text = I18n.format("travellers.worker.formation.summary.armour." + tiers[t].name().toLowerCase(Locale.ROOT))
                    + " " + Math.round(100F * g.armour[t] / g.members) + "%";
            int w = SWATCH + 3 + fr.getStringWidth(text);
            if (cx > 0 && cx + w > width) {
                lines++;
                cx = 0;
            }
            if (draw) {
                int ly = y + (lines - 1) * LEGEND_LINE;
                Gui.drawRect(x + cx, ly + 2, x + cx + SWATCH, ly + 2 + SWATCH, ARMOUR_COLOURS[t]);
                fr.drawString(text, x + cx + SWATCH + 3, ly, TravellersGuiTheme.COL_TEXT_DIM);
            }
            cx += w + 8;
        }
        return lines;
    }
}
