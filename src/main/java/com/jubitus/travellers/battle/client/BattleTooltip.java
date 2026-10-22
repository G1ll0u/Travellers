package com.jubitus.travellers.battle.client;

import com.jubitus.travellers.gui.TravellersGuiTheme;
import com.jubitus.travellers.marker.client.ClientOwnerVisualsCache;
import com.jubitus.travellers.notify.BattleSummary;
import com.jubitus.travellers.notify.WorkerLifecycleNotifications;
import com.jubitus.travellers.notify.client.LocationFormat;
import com.jubitus.travellers.worldmap.gui.WorldMapMarkerFactory;
import com.jubitus.travellers.worldmap.net.MsgMapBattleUpdate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Balance of power of one battle, the same drawing on the map marker and the Formation Hub journal: a title with the
 * state, a tug-of-war bar of who still stands (the viewer's camp against the other), then one row per camp named after
 * who makes it up in faction colours, with its standing (heart) and fallen (skull). Sized to its content.
 */
public final class BattleTooltip {

    private static final int MAX_W = 220;
    private static final int PAD = 5;
    private static final int LINE = 11;
    private static final int TUG_H = 7;
    private static final int ICON = 8;
    private static final int COLUMN_GAP = 8;
    // Above tooltips (z 300), like the group summary.
    private static final float Z = 350F;

    private static final int COL_RAIDERS = 0xFF000000 | WorldMapMarkerFactory.COLOR_POP_RAIDER;
    private static final int COL_TRAVELLERS = 0xFF000000 | WorldMapMarkerFactory.COLOR_POP_TRAVELLER;
    private static final int COL_HENCHMEN = 0xFF000000 | WorldMapMarkerFactory.COLOR_POP_WORKER;
    private static final int COL_HEART = 0xFFFF5555;
    private static final int COL_RUNNING = 0xFFFFAA33;
    private static final int COL_WON = 0xFF66DD66;
    private static final int COL_LOST = 0xFFFF5555;
    private static final int COL_OVER = 0xFFC0C0C0;

    private static final ResourceLocation SKELETON = new ResourceLocation("textures/entity/skeleton/skeleton.png");
    private static final int NAMES_SHOWN = 3;

    /** One camp's row, named after who makes it up, each part in its faction colour. */
    private static final class Camp {
        final List<String> parts = new ArrayList<>();
        final List<Integer> colours = new ArrayList<>();
        final int standing, fallen;
        /** Filled on the left of the tug-of-war bar (the viewer's camp, or the first of two strangers). */
        final boolean left;
        /** Its colour on the bar: the part with the most fighters. */
        int barColour;
        private int barCount = -1;

        Camp(int standing, int fallen, boolean left) {
            this.standing = standing;
            this.fallen = fallen;
            this.left = left;
        }

        Camp part(String name, int colour, int count) {
            if (count <= 0) return this;
            parts.add(name);
            colours.add(colour);
            if (count > barCount) {
                barCount = count;
                barColour = colour;
            }
            return this;
        }

        int nameWidth(FontRenderer fr) {
            int w = 0;
            for (int i = 0; i < parts.size(); i++) w += fr.getStringWidth(parts.get(i)) + (i > 0 ? fr.getStringWidth(AND) : 0);
            return w;
        }
    }

    private static final String AND = " & ";

    @Nullable public final UUID id;
    private final String title;
    private final int titleColour;
    private final String corner;
    @Nullable private final String subtitle;
    private final List<Camp> camps = new ArrayList<>();
    private final List<String> footer = new ArrayList<>();

    private BattleTooltip(@Nullable UUID id, String title, int titleColour, String corner, @Nullable String subtitle) {
        this.id = id;
        this.title = title;
        this.titleColour = titleColour;
        this.corner = corner;
        this.subtitle = subtitle;
    }

    /** A map marker, seen from the viewer: their henchmen, allies and enemies, or two camps of strangers. */
    public static BattleTooltip forMap(MsgMapBattleUpdate.Entry e) {
        String ago = tip("ago", clock(e.seconds));
        BattleTooltip t;
        switch (e.result) {
            case RUNNING:
                t = new BattleTooltip(e.id, tip(e.campAssault ? "camp_assault" : "running"), COL_RUNNING, clock(e.seconds), null);
                break;
            case FIRST_WON:
                t = new BattleTooltip(e.id, e.involved ? tip("won") : tip("over", plainName(e.first)),
                        e.involved ? COL_WON : COL_OVER, ago, null);
                break;
            case SECOND_WON:
                t = new BattleTooltip(e.id, e.involved ? tip("lost") : tip("over", plainName(e.second)),
                        e.involved ? COL_LOST : COL_OVER, ago, null);
                break;
            default:
                t = new BattleTooltip(e.id, tip("broken_off"), COL_OVER, ago, null);
        }
        if (e.involved) {
            MsgMapBattleUpdate.Side s = e.first;
            t.camps.add(new Camp(s.yoursStanding, s.yoursFallen, true)
                    .part(tip("yours"), ownColour(), s.yoursStanding + s.yoursFallen));
            int allyStanding = s.standing - s.yoursStanding;
            int allyFallen = s.fallen - s.yoursFallen;
            if (allyStanding + allyFallen > 0) {
                t.camps.add(sideCamp(new Camp(allyStanding, allyFallen, true), s.raiders, s.travellers, s.hired, s.hiredColor));
            }
            MsgMapBattleUpdate.Side f = e.second;
            t.camps.add(sideCamp(new Camp(f.standing, f.fallen, false), f.raiders, f.travellers, f.hired, f.hiredColor));
        } else {
            for (MsgMapBattleUpdate.Side s : new MsgMapBattleUpdate.Side[]{e.first, e.second}) {
                t.camps.add(sideCamp(new Camp(s.standing, s.fallen, s == e.first), s.raiders, s.travellers, s.hired, s.hiredColor));
            }
        }
        return t;
    }

    /** A journal row: the owner's view at its latest beat, with the place and the fallen henchmen. */
    public static BattleTooltip forJournal(BattleSummary bs) {
        BattleSummary.Counts c = bs.counts;
        String time = c != null ? clock(c.seconds) : "";
        String place = LocationFormat.describe(bs.location, bs.dimension);
        BattleTooltip t;
        switch (bs.state) {
            case WON:
                t = new BattleTooltip(null, tip("won"), COL_WON, time, place);
                break;
            case LOST:
                t = new BattleTooltip(null, tip("lost"), COL_LOST, time, place);
                break;
            case BROKEN_OFF:
                t = new BattleTooltip(null, tip("broken_off"), COL_OVER, time, place);
                break;
            default:
                t = new BattleTooltip(null, tip("running"), COL_RUNNING, time, place);
        }
        if (c != null) {
            t.camps.add(new Camp(c.ownStanding, c.ownFallen, true).part(tip("yours"), ownColour(), c.ownStanding + c.ownFallen));
            if (c.allyStanding + c.allyFallen > 0) {
                t.camps.add(sideCamp(new Camp(c.allyStanding, c.allyFallen, true),
                        c.allyRaiders, c.allyTravellers, c.allyHenchmen, c.allyHenchmenColor));
            }
            t.camps.add(sideCamp(new Camp(c.foeStanding, c.foeFallen, false),
                    c.foeRaiders, c.foeTravellers, c.foeHenchmen, c.foeHenchmenColor));
        }
        t.addFallen(bs.ownWorkerCasualties);
        return t;
    }

    private static Camp sideCamp(Camp camp, int raiders, int travellers, int henchmen, int henchmenColour) {
        camp.part(tip("name.raiders"), COL_RAIDERS, raiders)
                .part(tip("name.travellers"), COL_TRAVELLERS, travellers)
                .part(tip("name.henchmen"), henchmenColour != 0 ? 0xFF000000 | henchmenColour : COL_HENCHMEN, henchmen);
        if (camp.parts.isEmpty()) camp.part(tip("enemies"), COL_RAIDERS, 1);
        return camp;
    }

    private static String plainName(MsgMapBattleUpdate.Side s) {
        return String.join(AND, sideCamp(new Camp(0, 0, true), s.raiders, s.travellers, s.hired, s.hiredColor).parts);
    }

    private static int ownColour() {
        return 0xFF000000 | ClientOwnerVisualsCache.getColor();
    }

    private void addFallen(List<BattleSummary.CasualtyEntry> casualties) {
        List<String> dead = new ArrayList<>();
        int tomorrow = 0, later = 0, noBed = 0, never = 0;
        for (BattleSummary.CasualtyEntry k : casualties) {
            if (!k.died) continue;
            dead.add(k.name);
            if (k.respawnOutlook == WorkerLifecycleNotifications.OUTLOOK_NO_BED) noBed++;
            else if (k.respawnOutlook == WorkerLifecycleNotifications.OUTLOOK_NO_RESPAWN) never++;
            else if (k.respawnOutlook <= 1) tomorrow++;
            else later++;
        }
        if (dead.isEmpty()) return;
        String shown = String.join(", ", dead.subList(0, Math.min(NAMES_SHOWN, dead.size())));
        footer.add(dead.size() > NAMES_SHOWN ? tip("fallen_more", shown, dead.size() - NAMES_SHOWN) : tip("fallen", shown));
        if (tomorrow > 0) footer.add(tip("outlook.tomorrow", tomorrow));
        if (later > 0) footer.add(tip("outlook.later", later));
        if (noBed > 0) footer.add(tip("outlook.no_bed", noBed));
        if (never > 0) footer.add(tip("outlook.never", never));
    }

    public void draw(FontRenderer fr, int mouseX, int mouseY, int screenW, int screenH) {
        // Hearts and skulls in two right-aligned columns, so every row's numbers line up.
        int numW = fr.getStringWidth("0");
        int nameW = 0;
        for (Camp c : camps) {
            numW = Math.max(numW, Math.max(fr.getStringWidth(Integer.toString(c.standing)), fr.getStringWidth(Integer.toString(c.fallen))));
            nameW = Math.max(nameW, c.nameWidth(fr));
        }
        int heartW = fr.getStringWidth("♥");
        int columnsW = heartW + 2 + numW + COLUMN_GAP + ICON + 2 + numW;
        int content = Math.max(nameW + COLUMN_GAP + columnsW, fr.getStringWidth(title) + COLUMN_GAP + fr.getStringWidth(corner));
        if (subtitle != null) content = Math.max(content, fr.getStringWidth(subtitle));
        for (String line : footer) content = Math.max(content, fr.getStringWidth(line));
        int w = Math.min(MAX_W, content + PAD * 2);

        int h = PAD + LINE + (subtitle != null ? LINE : 0) + TUG_H + 4 + camps.size() * LINE
                + (footer.isEmpty() ? 0 : 3 + footer.size() * LINE) + PAD - 2;
        int x = mouseX + 12;
        int y = mouseY - 12;
        if (x + w > screenW - 2) x = mouseX - w - 12;
        if (y + h > screenH - 2) y = screenH - h - 2;
        if (y < 2) y = 2;

        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.color(1F, 1F, 1F, 1F);
        GlStateManager.pushMatrix();
        GlStateManager.translate(0F, 0F, Z);

        Gui.drawRect(x, y, x + w, y + h, 0xF0100C08);
        TravellersGuiTheme.drawBorder(x, y, w, h, TravellersGuiTheme.COL_BORDER);

        int left = x + PAD;
        int right = x + w - PAD;
        int width = right - left;
        int rowY = y + PAD;
        fr.drawString(fr.trimStringToWidth(title, width - COLUMN_GAP - fr.getStringWidth(corner)), left, rowY, titleColour);
        fr.drawString(corner, right - fr.getStringWidth(corner), rowY, TravellersGuiTheme.COL_TEXT_DIM);
        rowY += LINE;
        if (subtitle != null) {
            fr.drawString(fr.trimStringToWidth(subtitle, width), left, rowY, TravellersGuiTheme.COL_TEXT_DIM);
            rowY += LINE;
        }

        drawTug(left, rowY, right);
        rowY += TUG_H + 4;

        int fallenNumR = right;
        int skullX = fallenNumR - numW - 2 - ICON;
        int standNumR = skullX - COLUMN_GAP;
        int heartX = standNumR - numW - 2 - heartW;
        int firstRowY = rowY;
        for (Camp c : camps) {
            int nx = left;
            for (int i = 0; i < c.parts.size(); i++) {
                if (i > 0) nx = fr.drawString(AND, nx, rowY, TravellersGuiTheme.COL_TEXT_DIM);
                nx = fr.drawString(c.parts.get(i), nx, rowY, c.colours.get(i));
            }
            fr.drawString("♥", heartX, rowY, COL_HEART);
            String s = Integer.toString(c.standing);
            fr.drawString(s, standNumR - fr.getStringWidth(s), rowY, TravellersGuiTheme.COL_TEXT);
            String f = Integer.toString(c.fallen);
            fr.drawString(f, fallenNumR - fr.getStringWidth(f), rowY, TravellersGuiTheme.COL_TEXT_DIM);
            rowY += LINE;
        }
        drawSkulls(skullX, firstRowY, camps.size());

        if (!footer.isEmpty()) rowY += 3;
        for (String line : footer) {
            fr.drawString(fr.trimStringToWidth(line, width), left, rowY, TravellersGuiTheme.COL_TEXT_DIM);
            rowY += LINE;
        }

        GlStateManager.popMatrix();
        GlStateManager.color(1F, 1F, 1F, 1F);
    }

    /** Who still stands: the left camps' colours pushing against the right camps', each camp its own segment. */
    private void drawTug(int left, int y, int right) {
        int total = 0;
        for (Camp c : camps) total += c.standing;
        Gui.drawRect(left, y, right, y + TUG_H, 0xFF000000);
        TravellersGuiTheme.drawBorder(left, y, right - left, TUG_H, TravellersGuiTheme.COL_BORDER);
        if (total == 0) return;
        int inner = right - left - 2;
        int done = 0;
        int segX = left + 1;
        // Left camps first, then the right ones, so the meeting point shows who dominates.
        for (int pass = 0; pass < 2; pass++) {
            for (Camp c : camps) {
                if (c.left != (pass == 0) || c.standing == 0) continue;
                done += c.standing;
                int end = left + 1 + Math.round(inner * done / (float) total);
                Gui.drawRect(segX, y + 1, end, y + TUG_H - 1, c.barColour);
                segX = end;
            }
        }
    }

    /** A skeleton's face, seen from the front, at the start of each row's fallen column. */
    private static void drawSkulls(int x, int firstRowY, int rows) {
        Minecraft.getMinecraft().getTextureManager().bindTexture(SKELETON);
        GlStateManager.enableTexture2D();
        GlStateManager.color(1F, 1F, 1F, 1F);
        for (int i = 0; i < rows; i++) {
            Gui.drawModalRectWithCustomSizedTexture(x, firstRowY + i * LINE, 8, 8, ICON, ICON, 64, 32);
        }
    }

    private static String clock(int seconds) {
        return (seconds / 60) + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
    }

    private static String tip(String key, Object... args) {
        return I18n.format("travellers.battle.report." + key, args);
    }
}
