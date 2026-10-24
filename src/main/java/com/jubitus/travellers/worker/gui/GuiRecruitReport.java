package com.jubitus.travellers.worker.gui;

import com.jubitus.travellers.gui.GuiTravellersButton;
import com.jubitus.travellers.gui.ITravellersGui;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import com.jubitus.travellers.marker.client.ClientOwnerVisualsCache;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.village.MillCultures;
import com.jubitus.travellers.village.MillMoney;
import com.jubitus.travellers.worker.RecruitLog;
import com.jubitus.travellers.worker.RecruitReport;
import com.jubitus.travellers.worker.net.MsgRecruitTakePurse;
import com.jubitus.travellers.worldmap.gui.GuiWorldMapPanel;
import com.jubitus.travellers.worldmap.gui.WorldMapController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.client.config.GuiUtils;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** What a recruiter brings back: the route on the map, the recruits per culture, the purse to take. */
@SideOnly(Side.CLIENT)
public class GuiRecruitReport extends GuiScreen implements ITravellersGui {

    private static final int MAP_W = 190;
    private static final int MAP_H = 150;
    private static final int GAP = 14;
    private static final int LIST_W = 200;
    private static final int ROW_H = 14;
    private static final int HEADER_H = 12;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_GAP = 4;
    private static final float TEXT_SCALE = 0.85F;
    private static final int NAME_W = 76;
    private static final int PIP = 5, PIP_GAP = 2, MAX_PIPS = 10;
    private static final int MONEY_ROWS = 2;

    private RecruitReport report;
    private WorldMapController mapController;
    private GuiButton closeButton;
    private GuiButton takeButton;
    private int mapX, mapY, listX, listY, listH;

    public GuiRecruitReport(RecruitReport report) {
        this.report = report;
    }

    public boolean isFor(int entityId) {
        return report.entityId == entityId;
    }

    public void update(RecruitReport report) {
        this.report = report;
        refreshTakeButton();
        rebuildMarkers();
    }

    @Override
    public void initGui() {
        super.initGui();
        mapX = Math.max(4, (this.width - (MAP_W + GAP + LIST_W)) / 2);
        mapY = (this.height - MAP_H) / 2;
        listX = mapX + MAP_W + GAP;
        listY = mapY + HEADER_H + 2;
        int buttonsTop = mapY + MAP_H - BUTTON_H;
        listH = buttonsTop - BUTTON_GAP - 2 - listY;

        this.buttonList.clear();
        int bw = (LIST_W - BUTTON_GAP) / 2;
        closeButton = addButton(new GuiTravellersButton(0, listX, buttonsTop, bw, BUTTON_H, I18n.format("travellers.gui.close")));
        takeButton = addButton(new GuiTravellersButton(1, listX + LIST_W - bw, buttonsTop, bw, BUTTON_H, ""));
        refreshTakeButton();

        mapController = new WorldMapController(Minecraft.getMinecraft(), mapX, mapY, MAP_W, MAP_H,
                "travellers_recruit_report_map", mc.player.getPosition());
        frameRoute();
        rebuildMarkers();
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
        mapController.setCenter(new BlockPos((minX + maxX) / 2, 64, (minZ + maxZ) / 2));
        float bpp = Math.max((maxX - minX) / (float) MAP_W, (maxZ - minZ) / (float) MAP_H) * 1.25F;
        mapController.panel.setZoomBlocksPerPixel(Math.max(1.0F, bpp));
    }

    private void rebuildMarkers() {
        if (mapController == null) return;
        GuiWorldMapPanel panel = mapController.panel;
        panel.clearMarkers();
        int order = 0;
        for (RecruitReport.StopLine s : report.stops) {
            String name = s.name.isEmpty() ? s.pos.getX() + "/" + s.pos.getZ() : s.name;
            if (s.state == RecruitLog.SKIPPED) {
                panel.addMarker(new GuiWorldMapPanel.Marker(s.pos, 0xFFFF4444, GuiWorldMapPanel.Marker.MarkerType.DESTINATION_CROSS,
                        I18n.format("travellers.worker.recruit.report.map.skipped", name)));
            } else if (s.state == RecruitLog.VISITED) {
                order++;
                panel.addMarker(new GuiWorldMapPanel.Marker(s.pos, 0xFFFFD060, GuiWorldMapPanel.Marker.MarkerType.VILLAGE,
                        I18n.format("travellers.worker.recruit.report.map.visited", order, name, s.recruits)));
            } else {
                panel.addMarker(new GuiWorldMapPanel.Marker(s.pos, 0xFF9A8A70, GuiWorldMapPanel.Marker.MarkerType.VILLAGE,
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

    // ── Input ─────────────────────────────────────────────────────────────────

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
        int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && mapController != null && mapController.isMouseOver(mouseX, mouseY)) {
            mapController.handleWheel(mouseX, mouseY, wheel);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mapController != null && mapController.onMouseClick(mouseX, mouseY, mouseButton)) return;
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (mapController != null) mapController.onMouseDrag(mouseX, mouseY);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (mapController != null) mapController.onMouseRelease(state);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == closeButton) {
            mc.player.closeScreen();
        } else if (button == takeButton && report.left > 0) {
            TravellersNetwork.CHANNEL.sendToServer(new MsgRecruitTakePurse(report.entityId));
        }
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        TravellersGuiTheme.drawMapFrame(mapX, mapY, MAP_W, MAP_H);
        mapController.draw();
        super.drawScreen(mouseX, mouseY, partialTicks);

        fontRenderer.drawString(I18n.format("travellers.worker.recruit.report.title", report.name, duration(report.durationTicks)),
                listX, mapY + 1, TravellersGuiTheme.COL_TEXT);
        TravellersGuiTheme.drawInnerPanel(listX - 2, listY - 2, LIST_W + 4, listH + 4);
        int hovered = drawCultures(mouseX, mouseY);
        drawMoney();

        mapController.drawTooltip(this, mouseX, mouseY);
        if (hovered >= 0) drawCultureTooltip(report.cultures.get(hovered), mouseX, mouseY);
    }

    /** Draws one row per culture; returns the hovered one, or -1. */
    private int drawCultures(int mouseX, int mouseY) {
        int textY = (ROW_H - 7) / 2;
        int rows = Math.min(report.cultures.size(), (listH - MONEY_ROWS * ROW_H - 4) / ROW_H);
        int hovered = -1;
        for (int i = 0; i < rows; i++) {
            RecruitReport.CultureLine c = report.cultures.get(i);
            int top = listY + i * ROW_H;
            boolean hover = mouseX >= listX && mouseX < listX + LIST_W && mouseY >= top && mouseY < top + ROW_H;
            if (hover) hovered = i;
            TravellersGuiTheme.drawListRow(listX, top, LIST_W, ROW_H, hover, false);
            String name = TravellersGuiTheme.shortenMiddle(fontRenderer, MillCultures.adjective(c.key), "", (int) (NAME_W / TEXT_SCALE));
            TravellersGuiTheme.drawScaledText(fontRenderer, name, listX + 3, top + textY, TEXT_SCALE,
                    hover ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT);

            int x = listX + 3 + NAME_W + 4;
            if (c.wanted <= MAX_PIPS) {
                int pipY = top + (ROW_H - PIP) / 2;
                for (int p = 0; p < c.wanted; p++) {
                    int px = x + p * (PIP + PIP_GAP);
                    if (p < c.alive) {
                        Gui.drawRect(px, pipY, px + PIP, pipY + PIP, TravellersGuiTheme.COL_ACTIVE);
                    } else {
                        TravellersGuiTheme.drawBorder(px, pipY, PIP, PIP, TravellersGuiTheme.COL_BTN_BORDER_OFF);
                    }
                }
                x += c.wanted * (PIP + PIP_GAP) + 2;
            } else {
                String count = c.alive + "/" + c.wanted;
                TravellersGuiTheme.drawScaledText(fontRenderer, count, x, top + textY, TEXT_SCALE, TravellersGuiTheme.COL_ACTIVE);
                x += (int) Math.ceil(fontRenderer.getStringWidth(count) * TEXT_SCALE) + 4;
            }
            if (c.dead > 0) {
                TravellersGuiTheme.drawScaledText(fontRenderer, I18n.format("travellers.worker.recruit.report.dead_short", c.dead),
                        x, top + textY, TEXT_SCALE, TravellersGuiTheme.COL_DANGER_TEXT);
            }
        }
        return hovered;
    }

    private void drawMoney() {
        int top = listY + listH - MONEY_ROWS * ROW_H;
        TravellersGuiTheme.drawSeparator(listX, top - 2, LIST_W);
        moneyRow(top, I18n.format("travellers.worker.recruit.report.spent"), report.spent, TravellersGuiTheme.COL_TEXT_DIM);
        moneyRow(top + ROW_H, I18n.format("travellers.worker.recruit.report.left"), report.left,
                report.left > 0 ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT_DIM);
    }

    private void moneyRow(int top, String label, int deniers, int valueColor) {
        int textY = top + (ROW_H - 7) / 2;
        TravellersGuiTheme.drawScaledText(fontRenderer, label, listX + 3, textY, TEXT_SCALE, TravellersGuiTheme.COL_TEXT_DIM);
        String value = MillMoney.format(deniers);
        int w = (int) Math.ceil(fontRenderer.getStringWidth(value) * TEXT_SCALE);
        TravellersGuiTheme.drawScaledText(fontRenderer, value, listX + LIST_W - 3 - w, textY, TEXT_SCALE, valueColor);
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

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
