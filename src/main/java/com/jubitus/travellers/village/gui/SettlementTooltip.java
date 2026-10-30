package com.jubitus.travellers.village.gui;

import com.jubitus.travellers.gui.ItemIcon;
import com.jubitus.travellers.gui.TooltipFrame;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import com.jubitus.travellers.village.MillCultures;
import com.jubitus.travellers.village.net.SettlementMapInfo;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.List;

/** Hovering a village or lone building on a map: its kind, people, your standing there and what it is going through. */
@SideOnly(Side.CLIENT)
public final class SettlementTooltip {

    private static final int MIN_W = 130;
    private static final int MAX_W = 220;
    private static final int ICON = 9;
    private static final int COL_ALERT = 0xFF5555;
    private static final int COL_WARN = 0xFFAA44;

    private SettlementTooltip() {}

    public static void draw(FontRenderer fr, String name, SettlementMapInfo s, int mouseX, int mouseY, int screenW, int screenH) {
        String key = "travellers.map.settlement.";
        String people = I18n.format(key + "population", s.population);
        List<String> lines = new ArrayList<>();
        List<Integer> colours = new ArrayList<>();

        String culture = MillCultures.adjective(s.cultureKey);
        String type = MillCultures.settlementTypeName(s.cultureKey, s.typeKey, s.lone);
        add(lines, colours, type.isEmpty() ? culture : I18n.format(key + "kind", culture, type), TravellersGuiTheme.COL_TEXT);
        String reputation = MillCultures.villageReputationLabel(s.cultureKey, s.reputation);
        if (!reputation.isEmpty()) add(lines, colours, I18n.format(key + "reputation", reputation), TravellersGuiTheme.COL_TEXT);
        if (!s.controller.isEmpty()) add(lines, colours, I18n.format(key + "controlled_by", s.controller), TravellersGuiTheme.COL_TEXT);
        if (s.underAttack) add(lines, colours, I18n.format(key + "under_attack"), COL_ALERT);
        if (!s.raiding.isEmpty()) add(lines, colours, I18n.format(key + "raiding", s.raiding), COL_WARN);
        if (s.enemy) add(lines, colours, I18n.format(key + "enemy"), COL_ALERT);

        ItemStack icon = MillCultures.settlementIcon(s.cultureKey, s.typeKey, s.lone);
        int nameX = icon != null ? ICON + 3 : 0;
        int w = TooltipFrame.PAD * 3 + nameX + fr.getStringWidth(name) + fr.getStringWidth(people);
        for (String line : lines) w = Math.max(w, TooltipFrame.PAD * 2 + fr.getStringWidth(line));
        w = Math.max(MIN_W, Math.min(MAX_W, w));
        int h = TooltipFrame.PAD + TooltipFrame.LINE + lines.size() * TooltipFrame.LINE + TooltipFrame.PAD;

        int[] at = TooltipFrame.open(w, h, mouseX, mouseY, screenW, screenH);
        int left = at[0] + TooltipFrame.PAD;
        int right = at[0] + w - TooltipFrame.PAD;
        int rowY = at[1] + TooltipFrame.PAD;
        if (icon != null) ItemIcon.draw(icon, left, rowY - 1, ICON);
        int nameW = w - TooltipFrame.PAD * 3 - nameX - fr.getStringWidth(people);
        fr.drawString(fr.trimStringToWidth(name, nameW), left + nameX, rowY, TravellersGuiTheme.COL_TEXT_HOV);
        fr.drawString(people, right - fr.getStringWidth(people), rowY, TravellersGuiTheme.COL_TEXT_DIM);
        rowY += TooltipFrame.LINE;
        for (int i = 0; i < lines.size(); i++) {
            fr.drawString(fr.trimStringToWidth(lines.get(i), w - TooltipFrame.PAD * 2), left, rowY, colours.get(i));
            rowY += TooltipFrame.LINE;
        }
        TooltipFrame.close();
    }

    private static void add(List<String> lines, List<Integer> colours, String line, int colour) {
        lines.add(line);
        colours.add(colour);
    }
}
