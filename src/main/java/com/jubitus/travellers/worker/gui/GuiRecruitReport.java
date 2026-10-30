package com.jubitus.travellers.worker.gui;

import com.jubitus.travellers.gui.GuiMapListScreen;
import com.jubitus.travellers.gui.GuiTravellersButton;
import com.jubitus.travellers.gui.MapListLayout;
import com.jubitus.travellers.gui.MoneyText;
import com.jubitus.travellers.gui.ScrollList;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import com.jubitus.travellers.marker.client.ClientOwnerVisualsCache;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.village.MillCultures;
import com.jubitus.travellers.village.MillMoney;
import com.jubitus.travellers.worker.RecruitLog;
import com.jubitus.travellers.worker.RecruitReport;
import com.jubitus.travellers.worker.net.MsgRecruitTakePurse;
import com.jubitus.travellers.worldmap.gui.GuiWorldMapPanel;
import com.jubitus.travellers.worldmap.gui.WorldMapMarkerFactory;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.client.config.GuiUtils;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** What a recruiter brings back: the route on the map, the recruits per culture, the purse to take. */
@SideOnly(Side.CLIENT)
public class GuiRecruitReport extends GuiMapListScreen {

    private static final int NAME_W = 80;
    private static final int PIP = 5, PIP_GAP = 2, MAX_PIPS = 10;
    private static final int MONEY_ROWS = 2;

    private RecruitReport report;
    private GuiButton closeButton;
    private GuiButton takeButton;

    public GuiRecruitReport(RecruitReport report) {
        this.report = report;
    }

    public boolean isFor(int entityId) {
        return report.entityId == entityId;
    }

    public void update(RecruitReport report) {
        this.report = report;
        refreshTakeButton();
    }

    @Override protected String title() {
        return I18n.format("travellers.worker.recruit.report.title", report.name, duration(report.durationTicks));
    }
    @Override protected String mapKey() { return "travellers_recruit_report_map"; }
    @Override protected BlockPos home() { return mc.player.getPosition(); }
    @Override protected int sideWidth() { return 230; }
    @Override protected int listFooterH() { return MONEY_ROWS * list.rowH + 4; }
    @Override protected int rowCount() { return report.cultures.size(); }

    @Override
    protected void addControls() {
        int bw = layout.sideButtonW(2);
        closeButton = addButton(new GuiTravellersButton(FIRST_FREE_ID, layout.sideButtonX(0, 2), layout.sideRowY(0), bw,
                MapListLayout.BTN_H, I18n.format("travellers.gui.close")));
        takeButton = addButton(new GuiTravellersButton(FIRST_FREE_ID + 1, layout.sideButtonX(1, 2), layout.sideRowY(0), bw,
                MapListLayout.BTN_H, ""));
        refreshTakeButton();
    }

    @Override
    protected void onOpen() {
        map.setExtraMarkers(this::addRouteMarkers);
        frameRoute();
    }

    private void refreshTakeButton() {
        if (takeButton == null) return;
        takeButton.enabled = report.left > 0;
        takeButton.displayString = report.left > 0
                ? I18n.format("travellers.worker.recruit.report.take", MillMoney.format(report.left))
                : I18n.format("travellers.worker.recruit.report.taken");
    }

    // The whole route and the player in view.
    private void frameRoute() {
        int minX = mc.player.getPosition().getX(), maxX = minX, minZ = mc.player.getPosition().getZ(), maxZ = minZ;
        for (RecruitReport.StopLine s : report.stops) {
            minX = Math.min(minX, s.pos.getX());
            maxX = Math.max(maxX, s.pos.getX());
            minZ = Math.min(minZ, s.pos.getZ());
            maxZ = Math.max(maxZ, s.pos.getZ());
        }
        map.controller.setCenter(new BlockPos((minX + maxX) / 2, 64, (minZ + maxZ) / 2));
        float bpp = Math.max((maxX - minX) / (float) layout.mapW(), (maxZ - minZ) / (float) layout.mapH()) * 1.25F;
        map.controller.panel.setZoomBlocksPerPixel(Math.max(1.0F, bpp));
    }

    private void addRouteMarkers(GuiWorldMapPanel panel) {
        int order = 0;
        for (RecruitReport.StopLine s : report.stops) {
            String name = s.name.isEmpty() ? s.pos.getX() + "/" + s.pos.getZ() : s.name;
            if (s.state == RecruitLog.SKIPPED) {
                panel.addMarker(new GuiWorldMapPanel.Marker(s.pos, 0xFFFF4444, GuiWorldMapPanel.Marker.MarkerType.DESTINATION_CROSS,
                        I18n.format("travellers.worker.recruit.report.map.skipped", name)));
            } else if (s.state == RecruitLog.VISITED) {
                order++;
                panel.addMarker(WorldMapMarkerFactory.settlementDot(s.pos, 0xFFFFD060,
                        I18n.format("travellers.worker.recruit.report.map.visited", order, name, s.recruits)));
            } else {
                panel.addMarker(WorldMapMarkerFactory.settlementDot(s.pos, 0xFF9A8A70,
                        I18n.format("travellers.worker.recruit.report.map.not_reached", name)));
            }
        }
        int owner = 0xFF000000 | ClientOwnerVisualsCache.getColor();
        for (int i = 0; i < report.roadJoins.size(); i++) {
            panel.addMarker(new GuiWorldMapPanel.Marker(report.roadJoins.get(i), owner, GuiWorldMapPanel.Marker.MarkerType.FOLLOWER,
                    I18n.format("travellers.worker.recruit.report.map.road", report.roadJoinCounts.get(i))));
        }
        for (BlockPos p : report.deaths) {
            panel.addMarker(new GuiWorldMapPanel.Marker(p, 0xFFE0806E, GuiWorldMapPanel.Marker.MarkerType.BATTLE_SKIRMISH,
                    I18n.format("travellers.worker.recruit.report.map.death")));
        }
    }

    @Override
    protected boolean onAction(GuiButton button) throws IOException {
        if (button == closeButton) {
            mc.player.closeScreen();
        } else if (button == takeButton) {
            if (report.left > 0) TravellersNetwork.CHANNEL.sendToServer(new MsgRecruitTakePurse(report.entityId));
        } else {
            return false;
        }
        return true;
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    @Override
    protected void drawRow(int idx, int x, int top, int rowW, boolean hovered, int mouseX, int mouseY) {
        RecruitReport.CultureLine c = report.cultures.get(idx);
        int textY = list.textY(fontRenderer, top);
        fontRenderer.drawString(TravellersGuiTheme.shortenMiddle(fontRenderer, MillCultures.adjective(c.key), "", NAME_W),
                x + ScrollList.TEXT_PAD, textY, hovered ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT);

        int px0 = x + ScrollList.TEXT_PAD + NAME_W + 4;
        if (c.wanted <= MAX_PIPS) {
            int pipY = top + (list.rowH - PIP) / 2;
            for (int p = 0; p < c.wanted; p++) {
                int px = px0 + p * (PIP + PIP_GAP);
                if (p < c.alive) Gui.drawRect(px, pipY, px + PIP, pipY + PIP, TravellersGuiTheme.COL_ACTIVE);
                else TravellersGuiTheme.drawBorder(px, pipY, PIP, PIP, TravellersGuiTheme.COL_BTN_BORDER_OFF);
            }
            px0 += c.wanted * (PIP + PIP_GAP) + 2;
        } else {
            String count = c.alive + "/" + c.wanted;
            fontRenderer.drawString(count, px0, textY, TravellersGuiTheme.COL_ACTIVE);
            px0 += fontRenderer.getStringWidth(count) + 4;
        }
        if (c.dead > 0) {
            fontRenderer.drawString(I18n.format("travellers.worker.recruit.report.dead_short", c.dead), px0, textY,
                    TravellersGuiTheme.COL_DANGER_TEXT);
        }
    }

    @Override
    protected void drawSideOverlay(int mouseX, int mouseY) {
        int top = layout.listY() + layout.listH() - MONEY_ROWS * list.rowH;
        TravellersGuiTheme.drawSeparator(layout.listX(), top - 2, layout.listW());
        moneyRow(top, I18n.format("travellers.worker.recruit.report.spent"), report.spent, TravellersGuiTheme.COL_TEXT_DIM);
        moneyRow(top + list.rowH, I18n.format("travellers.worker.recruit.report.left"), report.left,
                report.left > 0 ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT_DIM);
    }

    private void moneyRow(int top, String label, int deniers, int valueColor) {
        int textY = list.textY(fontRenderer, top);
        fontRenderer.drawString(label, layout.listX() + ScrollList.TEXT_PAD, textY, TravellersGuiTheme.COL_TEXT_DIM);
        String value = MillMoney.format(deniers);
        MoneyText.draw(fontRenderer, value, layout.listX() + layout.listW() - ScrollList.TEXT_PAD - MoneyText.width(fontRenderer, value),
                textY, valueColor);
    }

    @Override
    protected void drawTop(int mouseX, int mouseY) {
        int idx = list.rowAt(mouseX, mouseY, report.cultures.size());
        if (idx >= 0) drawCultureTooltip(report.cultures.get(idx), mouseX, mouseY);
    }

    private void drawCultureTooltip(RecruitReport.CultureLine c, int mouseX, int mouseY) {
        List<String> lines = new ArrayList<>();
        lines.add(MillCultures.adjective(c.key));
        lines.add(I18n.format("travellers.worker.recruit.report.tip.found", c.alive, c.wanted));
        lines.add(I18n.format("travellers.worker.recruit.report.tip.where", c.inVillages, c.onRoad));
        if (c.dead > 0) lines.add(I18n.format("travellers.worker.recruit.report.tip.dead", c.dead));
        GuiUtils.drawHoveringText(lines, mouseX, mouseY, width, height, -1, fontRenderer);
    }

    private static String duration(long ticks) {
        long minutes = ticks / 20L / 60L;
        return minutes >= 60 ? (minutes / 60) + "h" + String.format("%02d", minutes % 60) : minutes + " min";
    }
}
