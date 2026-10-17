package com.jubitus.travellers.follow.gui;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import com.jubitus.travellers.notify.BattleSummary;
import com.jubitus.travellers.notify.JournalEntry;
import com.jubitus.travellers.notify.WorkerLifecycleNotifications;
import com.jubitus.travellers.notify.client.ClientJournalCache;
import com.jubitus.travellers.notify.client.LocationFormat;
import com.jubitus.travellers.notify.client.NoticeText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** The Formation Hub's journal panel: recent notices, newest at the bottom, follows new entries unless scrolled up. */
final class FormationHubJournal {

    private static final int PAD = 3;
    private static final int GUTTER_W = 6;

    private int x, y, w, h;
    // Index of the first visible entry.
    private int scroll = 0;
    private boolean pinnedToBottom = true;
    private int lastCount = -1;

    void setBounds(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    /** On open: show the newest entries. */
    void reset() {
        pinnedToBottom = true;
        scroll = Integer.MAX_VALUE;
        lastCount = -1;
    }

    void tick() {
        int count = ClientJournalCache.entries().size();
        if (count != lastCount) {
            lastCount = count;
            if (pinnedToBottom) scroll = Integer.MAX_VALUE;
        }
    }

    boolean isShown() {
        return h > 0;
    }

    boolean isOver(int mouseX, int mouseY) {
        return h > 0 && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    void scrollBy(int wheel) {
        scroll += wheel > 0 ? -1 : 1;
        clampScroll(ClientJournalCache.entries(), h - PAD * 2);
    }

    /** interactive: rows react to the mouse (false while a menu is open). */
    void draw(int mouseX, int mouseY, boolean interactive) {
        if (h <= 0) return;
        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer fr = mc.fontRenderer;
        TravellersGuiTheme.drawInnerPanel(x, y, w, h);
        int innerX = x + PAD, innerY = y + PAD;
        int innerW = w - PAD * 2 - GUTTER_W;
        int innerH = h - PAD * 2;

        if (!ClientJournalCache.isLoaded()) {
            fr.drawString(I18n.format("travellers.gui.history.loading"), innerX, innerY, TravellersGuiTheme.COL_TEXT_DIM);
            return;
        }
        List<JournalEntry> entries = ClientJournalCache.entries();
        if (entries.isEmpty()) {
            fr.drawString(I18n.format("travellers.gui.history.empty"), innerX, innerY, TravellersGuiTheme.COL_TEXT_DIM);
            return;
        }

        float fontScale = TravellersConfig.NOTIFICATIONS.historyPanelFontScale;
        clampScroll(entries, innerH);
        long worldTime = mc.world != null ? mc.world.getTotalWorldTime() : 0L;
        boolean showBattleIcon = TravellersConfig.NOTIFICATIONS.historyPanelBattleTooltipEnabled;

        int rowTop = innerY;
        int shown = 0;
        for (int idx = scroll; idx < entries.size(); idx++) {
            JournalEntry entry = entries.get(idx);
            String[] lines = lines(entry, worldTime);
            int rowH = entryHeight(lines.length);
            if (shown > 0 && rowTop + rowH > innerY + innerH) break;
            shown++;

            // Only a located entry reacts to the mouse: clicking it centers the map there.
            boolean hovered = interactive && entry.place != null && mouseX >= innerX && mouseX < innerX + innerW
                    && mouseY >= rowTop && mouseY < rowTop + rowH;
            TravellersGuiTheme.drawListRow(innerX, rowTop, innerW, rowH, hovered, false);
            Gui.drawRect(innerX + 1, rowTop + rowH / 2 - 2, innerX + 5, rowTop + rowH / 2 + 2, entry.type.color);

            boolean hasBattle = showBattleIcon && entry.battleSummary != null;
            int iconW = hasBattle ? 10 : 0;
            int textMaxW = Math.max(10, (int) ((innerW - 8 - iconW) / fontScale) - 4);
            float lineH = lineHeight();
            GlStateManager.pushMatrix();
            GlStateManager.translate(innerX + 8, rowTop + (rowH - lineH * lines.length) / 2f, 0f);
            GlStateManager.scale(fontScale, fontScale, 1f);
            for (int l = 0; l < lines.length; l++) {
                fr.drawString(fr.trimStringToWidth(lines[l], textMaxW), 0,
                        Math.round(l * lineH / fontScale), TravellersGuiTheme.COL_TEXT);
            }
            GlStateManager.popMatrix();
            if (hasBattle) {
                fr.drawString("⚔", innerX + innerW - 10, rowTop + (rowH - fr.FONT_HEIGHT) / 2, 0xFFCCCCCC);
            }
            rowTop += rowH;
        }
        // In the strip reserved on the right, so it never covers row content.
        TravellersGuiTheme.drawScrollbar(innerX + innerW + 2, innerY, innerH, scroll, shown, entries.size(), false);
    }

    @Nullable
    JournalEntry entryAt(int mouseX, int mouseY) {
        if (h <= 0) return null;
        int innerX = x + PAD, innerY = y + PAD;
        int innerW = w - PAD * 2 - GUTTER_W;
        int innerH = h - PAD * 2;
        if (mouseX < innerX || mouseX >= innerX + innerW || mouseY < innerY || mouseY >= innerY + innerH) return null;
        List<JournalEntry> entries = ClientJournalCache.entries();
        int rowTop = innerY;
        for (int idx = scroll; idx >= 0 && idx < entries.size(); idx++) {
            int rowH = entryHeight(entries.get(idx));
            if (idx > scroll && rowTop + rowH > innerY + innerH) break;
            if (mouseY < rowTop + rowH) return entries.get(idx);
            rowTop += rowH;
        }
        return null;
    }

    /** Headcounts, not names: a big battle reads in a few lines. Old rows (no headcounts) keep their foe names, capped. */
    static List<String> battleTooltip(BattleSummary bs) {
        List<String> lines = new ArrayList<>();
        BattleSummary.Counts c = bs.counts;
        if (c != null) lines.add(battleTitle(bs, c));
        String location = LocationFormat.describe(bs.location, bs.dimension);
        if (location != null) lines.add(location);
        if (c != null) {
            lines.add(tip("your_side", c.ownStanding, c.ownFallen));
            String allies = kinds(c.allyRaiders, c.allyTravellers, c.allyHenchmen);
            if (allies != null) lines.add(tip("allies", allies));
            lines.add(tip("enemies", foeKinds(c), c.foeStanding, c.foeFallen));
        }

        List<String> dead = new ArrayList<>();
        int tomorrow = 0, later = 0, noBed = 0, never = 0;
        for (BattleSummary.CasualtyEntry k : bs.ownWorkerCasualties) {
            if (!k.died) continue;
            dead.add(k.name);
            if (k.respawnOutlook == WorkerLifecycleNotifications.OUTLOOK_NO_BED) noBed++;
            else if (k.respawnOutlook == WorkerLifecycleNotifications.OUTLOOK_NO_RESPAWN) never++;
            else if (k.respawnOutlook <= 1) tomorrow++;
            else later++;
        }
        if (!dead.isEmpty()) {
            String shown = String.join(", ", dead.subList(0, Math.min(NAMES_SHOWN, dead.size())));
            lines.add(dead.size() > NAMES_SHOWN ? tip("losses_more", shown, dead.size() - NAMES_SHOWN) : tip("losses", shown));
            List<String> outlook = new ArrayList<>();
            if (tomorrow > 0) outlook.add(tip("outlook.tomorrow", tomorrow));
            if (later > 0) outlook.add(tip("outlook.later", later));
            if (noBed > 0) outlook.add(tip("outlook.no_bed", noBed));
            if (never > 0) outlook.add(tip("outlook.never", never));
            lines.add("  " + String.join(", ", outlook));
        }

        if (!bs.legacyAttackers.isEmpty()) {
            int n = bs.legacyAttackers.size();
            String shown = String.join(", ", bs.legacyAttackers.subList(0, Math.min(NAMES_SHOWN, n)));
            lines.add(n > NAMES_SHOWN ? tip("attackers_more", shown, n - NAMES_SHOWN) : tip("attackers", shown));
        }
        return lines;
    }

    private static final int NAMES_SHOWN = 3;

    private static String battleTitle(BattleSummary bs, BattleSummary.Counts c) {
        String time = (c.seconds / 60) + ":" + String.format("%02d", c.seconds % 60);
        return tip("title." + bs.state.name().toLowerCase(java.util.Locale.ROOT), foeKinds(c), time);
    }

    private static String foeKinds(BattleSummary.Counts c) {
        String foes = kinds(c.foeRaiders, c.foeTravellers, c.foeHenchmen);
        return foes != null ? foes : "?";
    }

    /** "80 raiders, 5 travellers", or null when all are zero. */
    @Nullable
    private static String kinds(int raiders, int travellers, int henchmen) {
        List<String> parts = new ArrayList<>();
        if (raiders > 0) parts.add(tip("kind.raiders", raiders));
        if (travellers > 0) parts.add(tip("kind.travellers", travellers));
        if (henchmen > 0) parts.add(tip("kind.henchmen", henchmen));
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static String tip(String key, Object... args) {
        return I18n.format("travellers.gui.history.battle_tooltip." + key, args);
    }

    /** Entry text split on '\n' (only the first line carries the time-ago prefix). */
    private static String[] lines(JournalEntry entry, long worldTime) {
        String text = NoticeText.format(entry.i18nKey, entry.args, entry.place, entry.detail);
        String[] lines = text.split("\n", -1);
        lines[0] = TextFormatting.GRAY + "[" + formatTimeAgo(worldTime - entry.worldTime) + "] "
                + TextFormatting.RESET + lines[0];
        return lines;
    }

    private static float lineHeight() {
        return Minecraft.getMinecraft().fontRenderer.FONT_HEIGHT * TravellersConfig.NOTIFICATIONS.historyPanelFontScale + 1f;
    }

    private static int entryHeight(int lineCount) {
        int minH = TravellersConfig.NOTIFICATIONS.historyPanelRowHeight;
        return lineCount <= 1 ? minH : Math.max(minH, (int) Math.ceil(lineHeight() * lineCount) + 2);
    }

    private static int entryHeight(JournalEntry entry) {
        return entryHeight(lines(entry, 0L).length);
    }

    /** The max scroll is the earliest start that still fits the newest entry at the bottom. */
    private void clampScroll(List<JournalEntry> entries, int innerH) {
        int total = entries.size();
        int idx = total;
        int used = 0;
        while (idx > 0) {
            int eh = entryHeight(entries.get(idx - 1));
            if (used + eh > innerH) break;
            used += eh;
            idx--;
        }
        int max = Math.max(0, Math.min(idx, total - 1));
        if (scroll > max) scroll = max;
        if (scroll < 0) scroll = 0;
        pinnedToBottom = scroll >= max;
    }

    private static String formatTimeAgo(long deltaTicks) {
        long seconds = Math.max(0, deltaTicks) / 20;
        if (seconds < 60) return I18n.format("travellers.gui.history.time.just_now");
        long minutes = seconds / 60;
        if (minutes < 60) return I18n.format("travellers.gui.history.time.minutes_ago", minutes);
        long hours = minutes / 60;
        if (hours < 24) return I18n.format("travellers.gui.history.time.hours_ago", hours);
        return I18n.format("travellers.gui.history.time.days_ago", hours / 24);
    }
}
