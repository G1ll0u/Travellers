package com.jubitus.travellers.worker.gui;

import com.jubitus.travellers.gui.GuiMapListScreen;
import com.jubitus.travellers.gui.GuiTravellersButton;
import com.jubitus.travellers.gui.MapListLayout;
import com.jubitus.travellers.gui.MoneyText;
import com.jubitus.travellers.gui.ScrollList;
import com.jubitus.travellers.gui.TravellersGuiTheme;
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
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.client.config.GuiUtils;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Recruiting order: how many recruits of each culture in range; the server plans the tour and its price. */
@SideOnly(Side.CLIENT)
public class GuiWorkerRecruitCultures extends GuiMapListScreen {

    private static final int HEADER_H = 12;
    private static final int BOX = 10;
    private static final int NAME_W = 80;
    private static final int COUNT_W = 16;

    private final EntityTravellerWorker worker;
    private final EntityPlayer player;

    private List<RecruitCultures.Option> options = new ArrayList<>();
    private final Map<String, Integer> wanted = new LinkedHashMap<>();
    private final Set<BlockPos> plannedVillages = new HashSet<>();
    private int travelFee;
    private int purse;
    private int maxTotal;
    private boolean received;

    private GuiButton backButton;
    private GuiButton startButton;

    public GuiWorkerRecruitCultures(EntityPlayer player, EntityTravellerWorker worker) {
        this.player = player;
        this.worker = worker;
    }

    @Override protected String title() { return I18n.format("travellers.worker.recruit.title"); }
    @Override protected String mapKey() { return "travellers_recruit_map"; }
    @Override protected BlockPos home() { return worker.getPosition(); }
    @Override protected int sideWidth() { return 250; }
    @Override protected int listHeaderH() { return HEADER_H; }
    @Override protected int goListWorkerId() { return worker.getEntityId(); }
    @Override protected int rowCount() { return options.size(); }
    @Override protected boolean isRowSelected(int idx) { return wanted.getOrDefault(options.get(idx).cultureKey, 0) > 0; }

    @Override
    protected String emptyListText() {
        return received ? I18n.format("travellers.worker.recruit.none") : null;
    }

    @Override
    protected void addControls() {
        int bw = layout.sideButtonW(2);
        backButton = addButton(new GuiTravellersButton(FIRST_FREE_ID, layout.sideButtonX(0, 2), layout.sideRowY(0), bw,
                MapListLayout.BTN_H, I18n.format("travellers.gui.back")));
        startButton = addButton(new GuiTravellersButton(FIRST_FREE_ID + 1, layout.sideButtonX(1, 2), layout.sideRowY(0), bw,
                MapListLayout.BTN_H, I18n.format("travellers.worker.recruit.start")));
        startButton.enabled = false;
    }

    @Override
    protected void onOpen() {
        map.setSelected(plannedVillages);
        requestPreview();
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
        maxTotal = msg.maxTotal;
        received = true;
        list.clamp(options.size());
        map.setSelected(plannedVillages);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        startButton.enabled = !plannedVillages.isEmpty() && MillMoney.countPlayerMoney(player) >= travelFee + purse;
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    @Override
    protected void onRowClicked(int idx, int mouseX, int mouseY) {
        RecruitCultures.Option o = options.get(idx);
        if (o.verdict != RecruitCultures.Verdict.OK) return;
        int rowTop = list.rowTop(idx);
        int step = isShiftKeyDown() ? 5 : 1;
        int count = wanted.getOrDefault(o.cultureKey, 0);
        if (inBox(mouseX, mouseY, minusX(), rowTop)) count -= step;
        else if (inBox(mouseX, mouseY, plusX(), rowTop) && room() > 0) count += Math.min(step, room());
        else return;
        count = Math.max(0, count);
        if (count == 0) wanted.remove(o.cultureKey);
        else wanted.put(o.cultureKey, count);
        requestPreview();
    }

    @Override
    protected boolean onAction(GuiButton button) throws IOException {
        if (button == backButton) {
            TravellersNetwork.CHANNEL.sendToServer(new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.HUB));
        } else if (button == startButton) {
            TravellersNetwork.CHANNEL.sendToServer(new MsgRecruitStart(worker.getEntityId(), new LinkedHashMap<>(wanted)));
            mc.player.closeScreen();
        } else {
            return false;
        }
        return true;
    }

    // Recruits that can still be added, all cultures together (the server caps the same total).
    private int room() {
        int total = 0;
        for (int n : wanted.values()) total += n;
        return maxTotal - total;
    }

    // ── Layout of a row ───────────────────────────────────────────────────────

    private int plusX() {
        return list.x + list.rowW(options.size()) - ScrollList.TEXT_PAD - BOX;
    }

    private int minusX() {
        return plusX() - COUNT_W - BOX;
    }

    private boolean inBox(int mouseX, int mouseY, int x, int rowTop) {
        int y = rowTop + (list.rowH - BOX) / 2;
        return mouseX >= x && mouseX < x + BOX && mouseY >= y && mouseY < y + BOX;
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    @Override
    protected void drawSideOverlay(int mouseX, int mouseY) {
        int y = layout.listY() + (HEADER_H - fontRenderer.FONT_HEIGHT) / 2;
        if (received) {
            String count = I18n.format("travellers.worker.recruit.count", maxTotal - room(), maxTotal);
            fontRenderer.drawString(count, layout.listX() + layout.listW() - 3 - fontRenderer.getStringWidth(count), y,
                    room() > 0 ? TravellersGuiTheme.COL_TEXT_DIM : TravellersGuiTheme.COL_TEXT);
        }
        if (!plannedVillages.isEmpty()) {
            boolean afford = MillMoney.countPlayerMoney(player) >= travelFee + purse;
            MoneyText.draw(fontRenderer, costLine(), layout.listX() + 3, y,
                    afford ? TravellersGuiTheme.COL_TEXT : TravellersGuiTheme.COL_CANNOT);
        }
        TravellersGuiTheme.drawSeparator(layout.listX(), layout.listY() + HEADER_H - 1, layout.listW());
    }

    @Override
    protected void drawRow(int idx, int x, int top, int rowW, boolean hovered, int mouseX, int mouseY) {
        RecruitCultures.Option o = options.get(idx);
        boolean ok = o.verdict == RecruitCultures.Verdict.OK;
        int count = wanted.getOrDefault(o.cultureKey, 0);
        int textY = list.textY(fontRenderer, top);
        int textCol = !ok ? TravellersGuiTheme.COL_TEXT_DIM : hovered ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT;

        fontRenderer.drawString(TravellersGuiTheme.shortenMiddle(fontRenderer, MillCultures.adjective(o.cultureKey), "", NAME_W),
                x + ScrollList.TEXT_PAD, textY, textCol);

        int repX = x + ScrollList.TEXT_PAD + NAME_W + 4;
        int repW = minusX() - 6 - repX;
        if (!ok) {
            String reason = I18n.format(o.verdict == RecruitCultures.Verdict.ENEMY_VILLAGE
                    ? "travellers.worker.recruit.blocked.enemy" : "travellers.worker.recruit.blocked.reputation");
            fontRenderer.drawString(fontRenderer.trimStringToWidth(reason, repW), repX, textY, TravellersGuiTheme.COL_CANNOT);
            return;
        }
        String chance = o.chancePercent + "%";
        int chanceW = fontRenderer.getStringWidth(chance);
        fontRenderer.drawString(fontRenderer.trimStringToWidth(MillCultures.localReputationLabel(o.cultureKey), repW - chanceW - 4),
                repX, textY, TravellersGuiTheme.COL_TEXT_DIM);
        fontRenderer.drawString(chance, repX + repW - chanceW, textY, textCol);

        drawBox(minusX(), top, "-", count > 0, hovered && inBox(mouseX, mouseY, minusX(), top));
        drawBox(plusX(), top, "+", room() > 0, hovered && inBox(mouseX, mouseY, plusX(), top));
        String n = String.valueOf(count);
        int numCenter = minusX() + BOX + COUNT_W / 2;
        fontRenderer.drawString(n, numCenter - fontRenderer.getStringWidth(n) / 2, textY,
                count > 0 ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT_DIM);
    }

    private String costLine() {
        return I18n.format("travellers.worker.recruit.cost", MillMoney.format(travelFee + purse));
    }

    private void drawBox(int x, int rowTop, String label, boolean enabled, boolean hover) {
        int y = rowTop + (list.rowH - BOX) / 2;
        TravellersGuiTheme.drawButtonBox(x, y, BOX, BOX,
                !enabled ? TravellersGuiTheme.COL_BTN_BG_OFF : hover ? TravellersGuiTheme.COL_BTN_BG_HOVER : TravellersGuiTheme.COL_BTN_BG,
                enabled ? TravellersGuiTheme.COL_BORDER : TravellersGuiTheme.COL_BTN_BORDER_OFF);
        TravellersGuiTheme.drawCenteredText(fontRenderer, label, x + BOX / 2 + 1, y + 1,
                !enabled ? TravellersGuiTheme.COL_BTN_TEXT_OFF : hover ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT);
    }

    @Override
    protected void drawTop(int mouseX, int mouseY) {
        int idx = list.rowAt(mouseX, mouseY, options.size());
        if (idx >= 0) {
            RecruitCultures.Option o = options.get(idx);
            boolean onBox = o.verdict == RecruitCultures.Verdict.OK
                    && (inBox(mouseX, mouseY, minusX(), list.rowTop(idx)) || inBox(mouseX, mouseY, plusX(), list.rowTop(idx)));
            if (!onBox) drawRowTooltip(o, mouseX, mouseY);
        } else if (isOverCost(mouseX, mouseY)) {
            drawCostTooltip(mouseX, mouseY);
        }
    }

    private boolean isOverCost(int mouseX, int mouseY) {
        int y = layout.listY();
        return !plannedVillages.isEmpty() && mouseY >= y && mouseY < y + HEADER_H
                && mouseX >= layout.listX() && mouseX < layout.listX() + 3 + MoneyText.width(fontRenderer, costLine());
    }

    private void drawCostTooltip(int mouseX, int mouseY) {
        List<String> lines = new ArrayList<>();
        lines.add(I18n.format("travellers.worker.recruit.cost.travel", MillMoney.format(travelFee)));
        lines.add(I18n.format("travellers.worker.recruit.cost.purse", MillMoney.format(purse)));
        lines.add(TextFormatting.GRAY + I18n.format("travellers.worker.recruit.cost.refund"));
        MoneyText.drawTooltip(fontRenderer, lines, mouseX, mouseY, width, height);
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
}
