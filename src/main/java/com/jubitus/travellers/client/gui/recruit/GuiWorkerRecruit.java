package com.jubitus.travellers.client.gui.recruit;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.MsgOpenWorkerSubGui;
import com.jubitus.travellers.server.net.message.recruit.MsgRecruitStartSelectedVillages;
import com.jubitus.travellers.server.net.message.villageshomes.MsgWorkerRequestVillages;
import com.jubitus.travellers.server.net.message.workerplayer.MsgRequestServerCosts;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Mouse;
import org.millenaire.common.utilities.MillCommonUtilities;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@SideOnly(Side.CLIENT)
public class GuiWorkerRecruit extends GuiScreen {

    private final EntityTravellerWorker worker;
    private final EntityPlayer player;
    private final List<VillageRow> villages = new ArrayList<>();
    private final java.util.Set<Integer> selected = new java.util.HashSet<>();

    private GuiButton startButton;
    private GuiButton clearButton;
    private GuiButton backButton;

    private int listX;
    private int listY;
    private int listW;
    private int listH;

    private final int rowH = 12;
    private int lastFee = 0;
    private int scrollOffset = 0;

    private int xSize = 176;
    private int ySize = 150;
    private int guiLeft;
    private int guiTop;

    public GuiWorkerRecruit(EntityPlayer player, EntityTravellerWorker worker) {
        this.player = player;
        this.worker = worker;
    }

    @Override
    public void initGui() {
        super.initGui();

        this.guiLeft = (this.width - this.xSize) / 2;
        this.guiTop = (this.height - this.ySize) / 2;

        int shiftLeft = 40;
        this.guiLeft = Math.max(4, this.guiLeft - shiftLeft);

        this.listW = 140;
        this.listH = 120;
        this.listX = this.guiLeft + this.xSize + 8;
        this.listY = this.guiTop + 4;

        this.buttonList.clear();

        int bw = 70;
        int bh = 16;
        int gap = 4;
        int buttonsTop = listY + listH + gap;

        this.startButton = this.addButton(new GuiButton(
                0,
                listX,
                buttonsTop,
                bw,
                bh,
                I18n.format("travellers.worker.recruit.start", "Recruit")
        ));

        this.clearButton = this.addButton(new GuiButton(
                1,
                listX + bw + gap,
                buttonsTop,
                bw,
                bh,
                I18n.format("travellers.gui.clear", "Clear")
        ));

        this.backButton = this.addButton(new GuiButton(
                2,
                listX + bw + gap,
                buttonsTop + bh + gap,
                bw,
                bh,
                I18n.format("travellers.gui.back", "Back")
        ));

        this.startButton.enabled = false;

        villages.clear();
        selected.clear();
        scrollOffset = 0;

        recomputeFee();

        TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerRequestVillages(worker.getEntityId()));
        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestServerCosts());
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        recomputeFee();
    }

    private boolean isMouseOverList(int mouseX, int mouseY) {
        return mouseX >= listX && mouseX < listX + listW
                && mouseY >= listY && mouseY < listY + listH;
    }

    private int getVisibleRows() {
        return Math.max(1, listH / rowH);
    }

    private int getMaxScroll() {
        return Math.max(0, villages.size() - getVisibleRows());
    }

    private void clampScroll() {
        if (scrollOffset < 0) scrollOffset = 0;
        int max = getMaxScroll();
        if (scrollOffset > max) scrollOffset = max;
    }

    private void recomputeFee() {
        if (startButton == null) return;

        if (selected.isEmpty()) {
            lastFee = 0;
            startButton.enabled = false;
            return;
        }

        java.util.List<Integer> sel = new java.util.ArrayList<>(selected);
        java.util.Collections.sort(sel);

        java.util.List<BlockPos> route = new java.util.ArrayList<>();
        for (int idx : sel) {
            if (idx >= 0 && idx < villages.size()) {
                route.add(villages.get(idx).center);
            }
        }

        BlockPos start = player.getPosition();
        BlockPos end = player.getPosition();

        double perBlock = TravellersModConfig.recruitFeePerBlock_serverCache;
        int flat = TravellersModConfig.recruitFeeFlat_serverCache;
        int perVillage = TravellersModConfig.recruitFeePerVillage_serverCache;

        boolean hasServer = flat >= 0 && perVillage >= 0 && !Double.isNaN(perBlock);

        int dist = EntityTravellerWorker.computeRouteDistance2D(start, route, end);

        if (hasServer) {
            lastFee = Math.max(0, flat + (perVillage * route.size()) + (int) Math.ceil(dist * perBlock));
        } else {
            lastFee = EntityTravellerWorker.computeRecruitFee(dist, route.size());
        }

        int money = MillCommonUtilities.countMoney(player.inventory);
        startButton.enabled = money >= lastFee;
    }

    private void handleListWheelScroll(int mouseX, int mouseY) {
        if (!isMouseOverList(mouseX, mouseY)) return;

        int wheel = org.lwjgl.input.Mouse.getDWheel();
        if (wheel == 0) return;

        int dir = wheel > 0 ? -1 : 1;
        scrollOffset += dir;
        clampScroll();
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();

        int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
        int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        handleListWheelScroll(mouseX, mouseY);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);

        if (mouseButton != 0 || villages.isEmpty()) return;
        if (!isMouseOverList(mouseX, mouseY)) return;

        int idx = scrollOffset + (mouseY - listY) / rowH;
        if (idx < 0 || idx >= villages.size()) return;

        int minD = TravellersModConfig.recruitMinVillageDistance;
        int dist = villages.get(idx).distance;

        if (dist < minD) {
            player.sendMessage(new TextComponentString(
                    TextFormatting.RED + I18n.format("travellers.worker.recruit.too_close", minD)
            ));
            return;
        }

        if (selected.contains(idx)) {
            selected.remove(idx);
        } else {
            selected.add(idx);
        }

        recomputeFee();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == clearButton) {
            selected.clear();
            recomputeFee();
            return;
        }

        if (button == backButton) {
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.ORDERS)
            );
            return;
        }

        if (button == startButton) {
            if (selected.isEmpty()) return;

            java.util.List<Integer> sel = new java.util.ArrayList<>(selected);
            java.util.Collections.sort(sel);

            java.util.List<BlockPos> route = new java.util.ArrayList<>();
            for (int idx : sel) {
                if (idx >= 0 && idx < villages.size()) {
                    route.add(villages.get(idx).center);
                }
            }

            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgRecruitStartSelectedVillages(worker.getEntityId(), route)
            );
            mc.player.closeScreen();
            return;
        }

        super.actionPerformed(button);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        super.drawScreen(mouseX, mouseY, partialTicks);

        drawVillageList(mouseX, mouseY);
    }

    private void drawVillageList(int mouseX, int mouseY) {
        drawRect(listX - 2, listY - 2, listX + listW + 2, listY + listH + 2, 0x80000000);

        int money = MillCommonUtilities.countMoney(player.inventory);
        String feeStr = MillCommonUtilities.getShortPrice(lastFee);
        int col = money >= lastFee ? 0x00FF00 : 0xFF5555;

        this.fontRenderer.drawString(
                I18n.format("travellers.worker.recruit.fee", feeStr),
                listX,
                listY - 10,
                col
        );

        clampScroll();

        int rowsToDraw = Math.min(getVisibleRows(), villages.size() - scrollOffset);
        for (int i = 0; i < rowsToDraw; i++) {
            int rowTop = listY + i * rowH;
            int rowBottom = rowTop + rowH;

            boolean hovered = mouseX >= listX && mouseX < listX + listW
                    && mouseY >= rowTop && mouseY < rowBottom;

            int globalIdx = scrollOffset + i;
            boolean isSel = selected.contains(globalIdx);

            int bg = isSel ? 0x4033FF33 : (hovered ? 0x20FFFFFF : 0x20000000);
            drawRect(listX, rowTop, listX + listW, rowBottom, bg);

            VillageRow row = villages.get(globalIdx);
            float scale = 0.85F;
            String label = makeRowLabel(row, scale);

            GlStateManager.pushMatrix();
            GlStateManager.translate(listX + 3, rowTop + 2, 0.0F);
            GlStateManager.scale(scale, scale, 1.0F);
            this.fontRenderer.drawString((isSel ? "[X] " : "[ ] ") + label, 0, 0, 0xE0E0E0);
            GlStateManager.popMatrix();
        }
    }

    private String makeRowLabel(VillageRow row, float scale) {
        String full = row.display;
        String distSuffix = " (" + row.distance + "m)";

        String baseName = full;
        int idx = full.lastIndexOf(" (");
        if (idx > 0 && full.endsWith("m)")) {
            baseName = full.substring(0, idx);
        }

        int maxUnscaledWidth = (int) ((this.listW - 6) / scale);
        return shortenNameWithSuffix(baseName, distSuffix, maxUnscaledWidth);
    }

    private String shortenNameWithSuffix(String name, String suffix, int maxWidth) {
        int fullWidth = this.fontRenderer.getStringWidth(name + suffix);
        if (fullWidth <= maxWidth) return name + suffix;

        int suffixWidth = this.fontRenderer.getStringWidth(suffix);
        if (suffixWidth >= maxWidth) return this.fontRenderer.trimStringToWidth(suffix, maxWidth);

        int remaining = maxWidth - suffixWidth;

        int nameWidth = this.fontRenderer.getStringWidth(name);
        if (nameWidth <= remaining) return name + suffix;

        String ellipsis = "...";
        int ellWidth = this.fontRenderer.getStringWidth(ellipsis);
        if (ellWidth >= remaining) return suffix;

        int half = (remaining - ellWidth) / 2;

        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            String candidate = prefix.toString() + name.charAt(i);
            if (this.fontRenderer.getStringWidth(candidate) > half) break;
            prefix.append(name.charAt(i));
        }

        StringBuilder tail = new StringBuilder();
        for (int i = name.length() - 1; i >= 0; i--) {
            String candidate = name.charAt(i) + tail.toString();
            int w = this.fontRenderer.getStringWidth(prefix + ellipsis + candidate);
            if (w > remaining) break;
            tail.insert(0, name.charAt(i));
        }

        return prefix + ellipsis + tail + suffix;
    }

    public void receiveVillageList(List<BlockPos> centers, List<String> labels, List<Integer> distances) {
        villages.clear();

        int n = Math.min(centers.size(), Math.min(labels.size(), distances.size()));
        for (int i = 0; i < n; i++) {
            villages.add(new VillageRow(centers.get(i), labels.get(i), distances.get(i)));
        }

        villages.sort(java.util.Comparator.comparingInt(v -> v.distance));

        selected.clear();
        scrollOffset = 0;
        recomputeFee();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    public Rectangle getJeiListArea() {
        return new Rectangle(
                this.listX - 2,
                this.listY - 12,
                this.listW + 4,
                this.listH + 38
        );
    }

    private static class VillageRow {
        final BlockPos center;
        final String display;
        final int distance;

        VillageRow(BlockPos center, String display, int distance) {
            this.center = center;
            this.display = display;
            this.distance = distance;
        }
    }
}