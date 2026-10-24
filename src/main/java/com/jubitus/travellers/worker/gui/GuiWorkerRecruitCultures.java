package com.jubitus.travellers.worker.gui;

import com.jubitus.travellers.gui.GuiTravellersButton;
import com.jubitus.travellers.gui.ITravellersGui;
import com.jubitus.travellers.gui.ScrollList;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import com.jubitus.travellers.marker.client.ClientOwnerVisualsCache;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.village.MillCultures;
import com.jubitus.travellers.village.MillMoney;
import com.jubitus.travellers.worker.RecruitCultures;
import com.jubitus.travellers.worker.RecruitPlan;
import com.jubitus.travellers.worker.net.MsgOpenWorkerSubGui;
import com.jubitus.travellers.worker.net.MsgRecruitPreview;
import com.jubitus.travellers.worker.net.MsgRecruitPreviewRequest;
import com.jubitus.travellers.worker.net.MsgRecruitStart;
import com.jubitus.travellers.worker.net.MsgWorkerGoList;
import com.jubitus.travellers.worker.net.MsgWorkerRequestGoList;
import com.jubitus.travellers.worldmap.gui.WorkerMapScreenSupport;
import com.jubitus.travellers.worldmap.gui.WorldMapController;
import com.jubitus.travellers.worldmap.gui.receiver.IWorkerGoListReceiver;
import com.jubitus.travellers.worldmap.gui.receiver.IWorkerMapReceiver;
import com.jubitus.travellers.worldmap.net.MsgMapWorkersUpdate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.client.config.GuiUtils;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Mouse;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Recruiting order: how many recruits of each culture in range; the server plans the tour and its price. */
@SideOnly(Side.CLIENT)
public class GuiWorkerRecruitCultures extends GuiScreen implements IWorkerGoListReceiver, IWorkerMapReceiver, ITravellersGui {

    private static final int MAP_W = 190;
    private static final int MAP_H = 150;
    private static final int GAP = 14;
    private static final int LIST_W = 200;
    private static final int ROW_H = 14;
    private static final int HEADER_H = 12;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_GAP = 4;
    private static final int BOX = 10;
    private static final float TEXT_SCALE = 0.85F;
    private static final int NAME_W = 76;

    private final EntityTravellerWorker worker;
    private final EntityPlayer player;
    private final ScrollList list = new ScrollList(ROW_H);
    private final WorkerMapScreenSupport mapData = new WorkerMapScreenSupport();
    private WorldMapController mapController;

    private List<RecruitCultures.Option> options = new ArrayList<>();
    private final Map<String, Integer> wanted = new LinkedHashMap<>();
    private final Set<BlockPos> plannedVillages = new HashSet<>();
    private int travelFee;
    private int purse;
    private boolean received;

    private GuiButton backButton;
    private GuiButton startButton;
    private int mapX, mapY, listX, listY, listH;

    public GuiWorkerRecruitCultures(EntityPlayer player, EntityTravellerWorker worker) {
        this.player = player;
        this.worker = worker;
    }

    @Override
    public void initGui() {
        super.initGui();
        mapX = Math.max(4, (this.width - (MAP_W + GAP + LIST_W)) / 2);
        mapY = (this.height - MAP_H) / 2;
        listX = mapX + MAP_W + GAP;
        // Header line, list panel and button row together span exactly the map's height.
        listY = mapY + HEADER_H + 2;
        int buttonsTop = mapY + MAP_H - BUTTON_H;
        listH = (buttonsTop - BUTTON_GAP - 2 - listY) / ROW_H * ROW_H;
        list.setBounds(listX, listY, LIST_W, listH);

        this.buttonList.clear();
        int bw = (LIST_W - BUTTON_GAP) / 2;
        backButton = addButton(new GuiTravellersButton(2, listX, buttonsTop, bw, BUTTON_H,
                I18n.format("travellers.gui.back")));
        startButton = addButton(new GuiTravellersButton(0, listX + LIST_W - bw, buttonsTop, bw, BUTTON_H,
                I18n.format("travellers.worker.recruit.start")));
        startButton.enabled = false;

        mapController = new WorldMapController(Minecraft.getMinecraft(), mapX, mapY, MAP_W, MAP_H,
                "travellers_recruit_map", worker.getPosition());
        mapData.subscribe();
        TravellersNetwork.CHANNEL.sendToServer(new MsgWorkerRequestGoList(worker.getEntityId()));
        requestPreview();
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        mapData.unsubscribe();
    }

    private void requestPreview() {
        TravellersNetwork.CHANNEL.sendToServer(new MsgRecruitPreviewRequest(worker.getEntityId(), new LinkedHashMap<>(wanted)));
    }

    public void receivePreview(MsgRecruitPreview msg) {
        if (msg.workerId != worker.getEntityId()) return;
        options = msg.options;
        wanted.clear();
        wanted.putAll(msg.wanted);
        plannedVillages.clear();
        for (RecruitPlan.Stop s : msg.stops) plannedVillages.add(s.center);
        travelFee = msg.travelFee;
        purse = msg.purse;
        received = true;
        list.clamp(options.size());
        rebuildMapMarkers();
    }

    @Override
    public void receiveGoList(List<MsgWorkerGoList.Entry> entries) {
        mapData.setPlaces(entries);
        rebuildMapMarkers();
    }

    @Override
    public void receiveWorkers(List<MsgMapWorkersUpdate.Entry> entries) {
        mapData.setWorkers(entries);
        rebuildMapMarkers();
    }

    private void rebuildMapMarkers() {
        if (mapController != null) mapData.populate(mapController, player, ClientOwnerVisualsCache.getColor(), plannedVillages);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        startButton.enabled = !plannedVillages.isEmpty() && MillMoney.countPlayerMoney(player) >= travelFee + purse;
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
        int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        if (mapController != null && mapController.isMouseOver(mouseX, mouseY)) {
            mapController.handleWheel(mouseX, mouseY, wheel);
        } else if (list.isOver(mouseX, mouseY)) {
            list.scrollBy(wheel, options.size());
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mapController != null && mapController.onMouseClick(mouseX, mouseY, mouseButton)) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0) return;
        int idx = list.rowAt(mouseX, mouseY, options.size());
        if (idx < 0) return;
        RecruitCultures.Option o = options.get(idx);
        if (o.verdict != RecruitCultures.Verdict.OK) return;
        int rowTop = list.rowTop(idx);
        int step = isShiftKeyDown() ? 5 : 1;
        int count = wanted.getOrDefault(o.cultureKey, 0);
        if (inBox(mouseX, mouseY, minusX(), rowTop)) count -= step;
        else if (inBox(mouseX, mouseY, plusX(), rowTop)) count += step;
        else return;
        count = Math.max(0, count);
        if (count == 0) wanted.remove(o.cultureKey);
        else wanted.put(o.cultureKey, count);
        requestPreview();
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
        if (button == backButton) {
            TravellersNetwork.CHANNEL.sendToServer(new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.HUB));
            return;
        }
        if (button == startButton) {
            TravellersNetwork.CHANNEL.sendToServer(new MsgRecruitStart(worker.getEntityId(), new LinkedHashMap<>(wanted)));
            mc.player.closeScreen();
            return;
        }
        super.actionPerformed(button);
    }

    // ── Layout of a row ───────────────────────────────────────────────────────

    private int plusX() {
        return listX + LIST_W - 3 - TravellersGuiTheme.SCROLLBAR_W - 2 - BOX;
    }

    private int minusX() {
        return plusX() - 18 - BOX;
    }

    private static boolean inBox(int mouseX, int mouseY, int x, int rowTop) {
        int y = rowTop + (ROW_H - BOX) / 2;
        return mouseX >= x && mouseX < x + BOX && mouseY >= y && mouseY < y + BOX;
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        if (mapController != null) {
            TravellersGuiTheme.drawMapFrame(mapX, mapY, MAP_W, MAP_H);
            rebuildMapMarkers();
            mapController.draw();
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
        drawHeader();
        int hovered = drawList(mouseX, mouseY);
        if (mapController != null) mapController.drawTooltip(this, mouseX, mouseY);
        if (hovered >= 0) drawRowTooltip(options.get(hovered), mouseX, mouseY);
    }

    private void drawHeader() {
        if (plannedVillages.isEmpty()) return;
        boolean afford = MillMoney.countPlayerMoney(player) >= travelFee + purse;
        fontRenderer.drawString(I18n.format("travellers.worker.recruit.travel_purse",
                        MillMoney.format(travelFee), MillMoney.format(purse)),
                listX, mapY + 1, afford ? TravellersGuiTheme.COL_AFFORD : TravellersGuiTheme.COL_CANNOT);
    }

    /** Draws the rows; returns the hovered option (outside its counter boxes), or -1. */
    private int drawList(int mouseX, int mouseY) {
        TravellersGuiTheme.drawInnerPanel(listX - 2, listY - 2, LIST_W + 4, listH + 4);
        if (received && options.isEmpty()) {
            TravellersGuiTheme.drawCenteredText(fontRenderer, I18n.format("travellers.worker.recruit.none"),
                    listX + LIST_W / 2, listY + listH / 2 - 4, TravellersGuiTheme.COL_TEXT_DIM);
            return -1;
        }
        list.clamp(options.size());
        int hovered = -1;
        int textY = (ROW_H - 7) / 2;
        for (int i = 0; i < list.rowsToDraw(options.size()); i++) {
            int idx = list.scroll() + i;
            RecruitCultures.Option o = options.get(idx);
            int rowTop = list.rowTop(idx);
            boolean ok = o.verdict == RecruitCultures.Verdict.OK;
            int count = wanted.getOrDefault(o.cultureKey, 0);
            boolean rowHover = list.isRowHovered(idx, mouseX, mouseY);
            boolean onBox = ok && (inBox(mouseX, mouseY, minusX(), rowTop) || inBox(mouseX, mouseY, plusX(), rowTop));
            if (rowHover && !onBox) hovered = idx;
            TravellersGuiTheme.drawListRow(listX, rowTop, LIST_W, ROW_H, rowHover, count > 0);

            int textCol = !ok ? TravellersGuiTheme.COL_TEXT_DIM
                    : rowHover ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT;
            String name = TravellersGuiTheme.shortenMiddle(fontRenderer, MillCultures.adjective(o.cultureKey), "",
                    (int) (NAME_W / TEXT_SCALE));
            TravellersGuiTheme.drawScaledText(fontRenderer, name, listX + 3, rowTop + textY, TEXT_SCALE, textCol);

            int repX = listX + 3 + NAME_W + 4;
            int repW = minusX() - 6 - repX;
            if (!ok) {
                String reason = I18n.format(o.verdict == RecruitCultures.Verdict.ENEMY_VILLAGE
                        ? "travellers.worker.recruit.blocked.enemy" : "travellers.worker.recruit.blocked.reputation");
                TravellersGuiTheme.drawScaledText(fontRenderer, reason, repX, rowTop + textY, TEXT_SCALE, TravellersGuiTheme.COL_CANNOT);
                continue;
            }
            String chance = o.chancePercent + "%";
            int chanceW = (int) Math.ceil(fontRenderer.getStringWidth(chance) * TEXT_SCALE);
            String rep = fontRenderer.trimStringToWidth(MillCultures.localReputationLabel(o.cultureKey),
                    (int) ((repW - chanceW - 4) / TEXT_SCALE));
            TravellersGuiTheme.drawScaledText(fontRenderer, rep, repX, rowTop + textY, TEXT_SCALE, TravellersGuiTheme.COL_TEXT_DIM);
            TravellersGuiTheme.drawScaledText(fontRenderer, chance, repX + repW - chanceW, rowTop + textY, TEXT_SCALE, textCol);

            drawBox(minusX(), rowTop, "-", count > 0, inBox(mouseX, mouseY, minusX(), rowTop));
            drawBox(plusX(), rowTop, "+", true, inBox(mouseX, mouseY, plusX(), rowTop));
            String n = String.valueOf(count);
            int numCenter = minusX() + BOX + (plusX() - minusX() - BOX) / 2;
            int nW = (int) Math.ceil(fontRenderer.getStringWidth(n) * TEXT_SCALE);
            TravellersGuiTheme.drawScaledText(fontRenderer, n, numCenter - nW / 2, rowTop + textY, TEXT_SCALE,
                    count > 0 ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT_DIM);
        }
        list.drawScrollbar(options.size());
        return hovered;
    }

    private void drawBox(int x, int rowTop, String label, boolean enabled, boolean hover) {
        int y = rowTop + (ROW_H - BOX) / 2;
        TravellersGuiTheme.drawButtonBox(x, y, BOX, BOX,
                !enabled ? TravellersGuiTheme.COL_BTN_BG_OFF : hover ? TravellersGuiTheme.COL_BTN_BG_HOVER : TravellersGuiTheme.COL_BTN_BG,
                enabled ? TravellersGuiTheme.COL_BORDER : TravellersGuiTheme.COL_BTN_BORDER_OFF);
        TravellersGuiTheme.drawCenteredText(fontRenderer, label, x + BOX / 2 + 1, y + 1,
                !enabled ? TravellersGuiTheme.COL_BTN_TEXT_OFF : hover ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT);
    }

    private void drawRowTooltip(RecruitCultures.Option o, int mouseX, int mouseY) {
        List<String> lines = new ArrayList<>();
        lines.add(MillCultures.adjective(o.cultureKey));
        lines.add(I18n.format("travellers.worker.recruit.tip.villages", o.villages, o.nearestDistance));
        lines.add(I18n.format("travellers.worker.recruit.tip.free", o.freeUnits));
        if (o.verdict == RecruitCultures.Verdict.OK) {
            lines.add(I18n.format("travellers.worker.recruit.tip.chance", o.chancePercent));
        } else {
            lines.add(I18n.format(o.verdict == RecruitCultures.Verdict.ENEMY_VILLAGE
                    ? "travellers.worker.recruit.tip.enemy" : "travellers.worker.recruit.tip.reputation"));
        }
        GuiUtils.drawHoveringText(lines, mouseX, mouseY, width, height, -1, fontRenderer);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    public Rectangle getJeiListArea() {
        return new Rectangle(listX - 2, mapY, LIST_W + 4, MAP_H);
    }

    public Rectangle getJeiMapArea() {
        return new Rectangle(mapX - 2, mapY - 2, MAP_W + 4, MAP_H + 4);
    }
}
